package de.htwk.uselsp;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tzi.use.config.Options;
import org.tzi.use.config.Options.WarningType;
import org.tzi.use.parser.ocl.OCLCompiler;
import org.tzi.use.parser.shell.ShellCommandCompiler;
import org.tzi.use.parser.soil.SoilCompiler;
import org.tzi.use.parser.soil.ast.ASTOperationCallStatement;
import org.tzi.use.parser.soil.ast.ASTStatement;
import org.tzi.use.parser.use.USECompiler;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.mm.ModelFactory;
import org.tzi.use.uml.ocl.extension.ExtensionManager;
import org.tzi.use.uml.ocl.value.VarBindings;
import org.tzi.use.util.Log;

/**
 * Checks models and Soil scripts with USE's own compiler and turns its output
 * into messages with positions. Not thread-safe (USE has global state); the
 * server calls it from a single thread only.
 */
final class UseChecker {

    enum Severity { ERROR, WARNING, INFO }

    /** A message; {@code line} counts from 1 (0: no position), {@code column} from 0. */
    record Message(int line, int column, String text, Severity severity) {
    }

    /** The messages for a model and the compiled model (null if there are errors). */
    record Checked(List<Message> messages, MModel model) {
    }

    private record Pass(List<Message> messages, String logged, MModel model) {
    }

    /** Input name for single Soil lines, as in the USE shell. */
    private static final String INPUT = "<input>";

    /** USE shell commands (Shell.processLine) that are valid only as a whole line. */
    private static final Set<String> COMMANDS = Set.of("q", "quit", "exit", "net", "reset", "step on",
            "undo", "redo", "genmonitor");

    /** USE shell commands with arguments. */
    private static final List<String> COMMAND_PREFIXES = List.of("help", "check", "genvcg", "genmm", "info",
            "open", "reopen", "read", "write", "constraints", "gen ", "reload extensions", "coverage", "plugins",
            "delay", "debug");

    /** Lines that look like a Soil statement without {@code !}. */
    private static final Pattern SOIL_WITHOUT_BANG = Pattern.compile(
            "(new|create|destroy|insert|delete|set|openter|opexit|declare)\\b.*"
                    + "|[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)?\\s*:=.*");

    /** {@code obj.attr = value} as a statement: comparison instead of assignment. */
    private static final Pattern COMPARISON = Pattern.compile(
            "^(\\s*[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*\\s*)=(?![=>])");

    private static final Method SHELL_PARSER = shellParser();

    private final MModel emptyModel = new ModelFactory().createModel("leer");

    UseChecker(Path useHome) {
        Log.setShowWarnings(true);
        // load the installation's OCL extensions, as USE does at startup
        if (useHome != null && Files.isDirectory(useHome.resolve("oclextensions"))) {
            ExtensionManager.EXTENSIONS_FOLDER = useHome.resolve("oclextensions").toString();
            ExtensionManager.getInstance().loadExtensions();
        }
    }

    /**
     * Checks a model like {@code use -c}. {@code base} is used to resolve
     * {@code import} and may be null.
     */
    Checked checkModel(String text, String name, URI base) {
        Pass first = compileModel(text, name, base);
        List<Message> messages = new ArrayList<>(first.messages());
        if (first.logged().contains("Warning")) {
            messages.addAll(locateWarnings(text, name, base, first));
        } else if (!first.logged().isBlank()) {
            System.err.print(first.logged());
        }
        return new Checked(messages, first.model());
    }

    /** Compiles a model without messages; null if it has errors. */
    MModel compile(String text, String name, URI base) {
        return compileModel(text, name, base).model();
    }

    private Pass compileModel(String text, String name, URI base) {
        StringWriter out = new StringWriter();
        PrintWriter err = new PrintWriter(out);
        InputStream in = stream(text);
        MModel[] model = new MModel[1];
        String logged = Capture.during(() -> model[0] = base != null
                ? USECompiler.compileSpecification(in, name, base, err, new ModelFactory())
                : USECompiler.compileSpecification(in, name, err, new ModelFactory()));
        err.flush();
        return new Pass(parse(out.toString(), name, false), logged, model[0]);
    }

    /**
     * USE prints type-check warnings (such as "will always evaluate to false")
     * without a position. A second run, in which these checks are errors, yields
     * the locations: whatever is new there are the warnings.
     */
    private List<Message> locateWarnings(String text, String name, URI base, Pass first) {
        WarningType unrelated = Options.checkWarningsUnrelatedTypes();
        WarningType oclAny = Options.checkWarningsOclAnyInCollections();
        Pass second;
        try {
            if (unrelated == WarningType.WARN) {
                Options.setCheckWarningsUnrelatedTypes(WarningType.ERROR);
            }
            if (oclAny == WarningType.WARN) {
                Options.setCheckWarningsOclAnyInCollections(WarningType.ERROR);
            }
            second = compileModel(text, name, base);
        } finally {
            Options.setCheckWarningsUnrelatedTypes(unrelated);
            Options.setCheckWarningsOclAnyInCollections(oclAny);
        }
        Set<String> known = new HashSet<>();
        for (Message m : first.messages()) {
            known.add(m.line() + ":" + m.column());
        }
        List<Message> warnings = new ArrayList<>();
        for (Message m : second.messages()) {
            if (m.line() > 0 && known.add(m.line() + ":" + m.column())) {
                warnings.add(new Message(m.line(), m.column(), m.text(), Severity.WARNING));
            }
        }
        if (warnings.isEmpty()) {
            String logged = first.logged().strip().replaceFirst("^Warning: ", "");
            warnings.add(new Message(0, 0, logged, Severity.WARNING));
        }
        return warnings;
    }

    /**
     * Checks a Soil script line by line, the way the USE shell reads it. Without
     * a system state (objects are often created in another script) there are only
     * syntax errors and unknown commands.
     */
    List<Message> checkSoil(String text) {
        List<Message> messages = new ArrayList<>();
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String command = line.strip();
            if (command.isEmpty() || command.startsWith("--") || command.startsWith("//")) {
                continue;
            }
            int start = line.indexOf(command);
            if (command.startsWith("!")) {
                int offset = start + (command.startsWith("!!") ? 2 : 1);
                if (line.substring(offset).isBlank()) {
                    messages.add(new Message(i + 1, start, "Statement expected.", Severity.ERROR));
                } else {
                    messages.addAll(checkStatement(line.substring(offset), i + 1, offset));
                }
            } else if (command.startsWith("?") || command.startsWith(":")) {
                int offset = start + (command.startsWith("??") ? 2 : 1);
                messages.addAll(checkExpression(line.substring(offset), i + 1, offset));
            } else if (!isShellCommand(command)) {
                String hint = SOIL_WITHOUT_BANG.matcher(command).matches()
                        ? " Soil-Anweisungen beginnen mit `!'." : "";
                messages.add(new Message(i + 1, start, "Unknown command `" + command + "'. Try `help'." + hint,
                        Severity.ERROR));
            }
        }
        return messages;
    }

    private List<Message> checkStatement(String statement, int line, int offset) {
        StringWriter out = new StringWriter();
        PrintWriter err = new PrintWriter(out);
        ASTStatement[] ast = new ASTStatement[1];
        Capture.during(() -> ast[0] = parseShellStatement(statement, err));
        err.flush();
        List<Message> messages = shift(parse(out.toString(), INPUT, false), line, offset);
        if (messages.isEmpty() && ast[0] instanceof ASTOperationCallStatement) {
            Matcher m = COMPARISON.matcher(statement);
            if (m.find()) {
                messages.add(new Message(line, offset + m.end(1), "In Soil wird mit `:=' zugewiesen; `=' ist ein "
                        + "Vergleich, den USE als Anweisung ablehnt (does not give a reference to an operation).",
                        Severity.ERROR));
            }
        }
        return messages;
    }

    /**
     * Parses a statement after {@code !} the way the USE shell does: with the grammar
     * ShellCommand, which besides Soil also knows the old forms ({@code openter},
     * {@code opexit}, {@code create x : C}). Its parse-only method is merely protected
     * in USE; if it is missing, the Soil grammar is used.
     */
    private static ASTStatement parseShellStatement(String statement, PrintWriter err) {
        if (SHELL_PARSER == null) {
            return SoilCompiler.constructAST(stream(statement), INPUT, err, false);
        }
        try {
            return (ASTStatement) SHELL_PARSER.invoke(null, stream(statement), INPUT, err, false);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method shellParser() {
        try {
            Method m = ShellCommandCompiler.class.getDeclaredMethod("constructAST", InputStream.class, String.class,
                    PrintWriter.class, boolean.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("ShellCommandCompiler.constructAST nicht verfügbar, prüfe nur Soil: " + e);
            return null;
        }
    }

    private List<Message> checkExpression(String expression, int line, int offset) {
        StringWriter out = new StringWriter();
        PrintWriter err = new PrintWriter(out);
        Capture.during(() -> OCLCompiler.compileExpression(emptyModel, expression, INPUT, err, new VarBindings()));
        err.flush();
        // against the empty model all names are unknown: only syntax errors count
        return shift(parse(out.toString(), INPUT, true), line, offset);
    }

    /** Moves messages for a single line to their position in the script. */
    private static List<Message> shift(List<Message> messages, int line, int offset) {
        List<Message> shifted = new ArrayList<>();
        for (Message m : messages) {
            int column = m.line() <= 1 ? offset + m.column() : m.column();
            shifted.add(new Message(line + Math.max(m.line(), 1) - 1, column, m.text(), m.severity()));
        }
        return shifted;
    }

    private static boolean isShellCommand(String command) {
        return COMMANDS.contains(command) || COMMAND_PREFIXES.stream().anyMatch(command::startsWith);
    }

    /**
     * Parses USE's error output. Syntax errors (ANTLR):
     * {@code name:line 16:5 no viable alternative ...}; type errors:
     * {@code name:45:52: Undefined operation ...}. Columns count from 0; lines
     * without this pattern continue the previous message.
     */
    private static List<Message> parse(String output, String name, boolean syntaxOnly) {
        Pattern syntax = Pattern.compile(Pattern.quote(name) + ":line (\\d+):(\\d+) (.*)");
        Pattern semantic = Pattern.compile(Pattern.quote(name) + ":(\\d+):(\\d+): (.*)");
        List<Message> messages = new ArrayList<>();
        boolean skipping = false;
        for (String line : output.split("\r\n|\r|\n")) {
            Matcher m;
            if ((m = syntax.matcher(line)).matches()) {
                messages.add(new Message(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3),
                        Severity.ERROR));
                skipping = false;
            } else if ((m = semantic.matcher(line)).matches()) {
                skipping = syntaxOnly;
                if (!syntaxOnly) {
                    messages.add(classify(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3)));
                }
            } else if (line.isBlank() || skipping || line.strip().equals("Compilation failed.")) {
                continue;
            } else if (!messages.isEmpty() && !line.startsWith("Error") && !line.startsWith("Warning")) {
                Message last = messages.remove(messages.size() - 1);
                messages.add(new Message(last.line(), last.column(), last.text() + "\n" + line.strip(),
                        last.severity()));
            } else if (!syntaxOnly) {
                messages.add(classify(0, 0, line.replaceFirst("^Error: ", "")));
            }
        }
        return messages;
    }

    private static Message classify(int line, int column, String text) {
        if (text.startsWith("Warning: ")) {
            return new Message(line, column, text.substring("Warning: ".length()), Severity.WARNING);
        }
        if (text.contains("previously declared here")) {
            return new Message(line, column, text, Severity.INFO);
        }
        return new Message(line, column, text, Severity.ERROR);
    }

    /** USE reads with the default charset (ANTLRInputStream); columns count characters. */
    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(Charset.defaultCharset()));
    }
}
