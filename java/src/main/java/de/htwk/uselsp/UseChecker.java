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
 * Prüft Modelle und Soil-Skripte mit dem Compiler von USE selbst und macht aus
 * dessen Ausgabe Meldungen mit Position. Nicht threadsicher (USE hat globalen
 * Zustand); der Server ruft es nur aus einem Thread auf.
 */
final class UseChecker {

    enum Severity { ERROR, WARNING, INFO }

    /** Eine Meldung; {@code line} zählt ab 1 (0: ohne Position), {@code column} ab 0. */
    record Message(int line, int column, String text, Severity severity) {
    }

    /** Die Meldungen zu einem Modell und das übersetzte Modell (null bei Fehlern). */
    record Checked(List<Message> messages, MModel model) {
    }

    private record Pass(List<Message> messages, String logged, MModel model) {
    }

    /** Eingabename für einzelne Soil-Zeilen, wie in der USE-Shell. */
    private static final String INPUT = "<input>";

    /** Befehle der USE-Shell (Shell.processLine), die nur als ganze Zeile gelten. */
    private static final Set<String> COMMANDS = Set.of("q", "quit", "exit", "net", "reset", "step on",
            "undo", "redo", "genmonitor");

    /** Befehle der USE-Shell mit Argumenten. */
    private static final List<String> COMMAND_PREFIXES = List.of("help", "check", "genvcg", "genmm", "info",
            "open", "reopen", "read", "write", "constraints", "gen ", "reload extensions", "coverage", "plugins",
            "delay", "debug");

    /** Zeilen, die wie eine Soil-Anweisung ohne {@code !} aussehen. */
    private static final Pattern SOIL_WITHOUT_BANG = Pattern.compile(
            "(new|create|destroy|insert|delete|set|openter|opexit|declare)\\b.*"
                    + "|[A-Za-z_]\\w*(\\.[A-Za-z_]\\w*)?\\s*:=.*");

    /** {@code obj.attr = wert} als Anweisung: Vergleich statt Zuweisung. */
    private static final Pattern COMPARISON = Pattern.compile(
            "^(\\s*[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*\\s*)=(?![=>])");

    private static final Method SHELL_PARSER = shellParser();

    private final MModel emptyModel = new ModelFactory().createModel("leer");

    UseChecker(Path useHome) {
        Log.setShowWarnings(true);
        // die OCL-Erweiterungen der Installation laden, wie USE beim Start
        if (useHome != null && Files.isDirectory(useHome.resolve("oclextensions"))) {
            ExtensionManager.EXTENSIONS_FOLDER = useHome.resolve("oclextensions").toString();
            ExtensionManager.getInstance().loadExtensions();
        }
    }

    /**
     * Prüft ein Modell wie {@code use -c}. {@code base} dient zum Auflösen von
     * {@code import} und darf null sein.
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

    /** Übersetzt ein Modell ohne Meldungen; null, wenn es Fehler hat. */
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
     * Warnungen der Typprüfung (etwa "will always evaluate to false") gibt USE
     * ohne Position aus. Ein zweiter Lauf, in dem diese Prüfungen Fehler sind,
     * liefert die Stellen: was dort neu hinzukommt, sind die Warnungen.
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
     * Prüft ein Soil-Skript Zeile für Zeile, so wie die USE-Shell es liest. Ohne
     * Systemzustand (Objekte entstehen oft in einem anderen Skript) gibt es nur
     * Syntaxfehler und unbekannte Befehle.
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
     * Zerlegt eine Anweisung nach {@code !} so wie die USE-Shell: mit der Grammatik
     * ShellCommand, die außer Soil auch die alten Formen kennt ({@code openter},
     * {@code opexit}, {@code create x : C}). Deren reine Syntaxanalyse ist in USE
     * nur protected; fehlt sie, bleibt die Soil-Grammatik.
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
        // gegen das leere Modell sind alle Namen unbekannt: nur Syntaxfehler zählen
        return shift(parse(out.toString(), INPUT, true), line, offset);
    }

    /** Verschiebt Meldungen zu einer einzelnen Zeile an ihre Stelle im Skript. */
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
     * Zerlegt die Fehlerausgabe von USE. Syntaxfehler (ANTLR):
     * {@code name:line 16:5 no viable alternative ...}; Typfehler:
     * {@code name:45:52: Undefined operation ...}. Spalten zählen ab 0, Zeilen
     * ohne dieses Muster setzen die vorige Meldung fort.
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

    /** USE liest mit dem Standardzeichensatz (ANTLRInputStream); Spalten zählen Zeichen. */
    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(Charset.defaultCharset()));
    }
}
