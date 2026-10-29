package de.htwk.uselsp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.InsertTextFormat;
import org.tzi.use.parser.SemanticException;
import org.tzi.use.parser.Symtable;
import org.tzi.use.parser.ocl.OCLCompiler;
import org.tzi.use.uml.mm.MAssociation;
import org.tzi.use.uml.mm.MAttribute;
import org.tzi.use.uml.mm.MClass;
import org.tzi.use.uml.mm.MModel;
import org.tzi.use.uml.mm.MNavigableElement;
import org.tzi.use.uml.mm.MOperation;
import org.tzi.use.uml.ocl.expr.ExpStdOp;
import org.tzi.use.uml.ocl.expr.Expression;
import org.tzi.use.uml.ocl.expr.VarDecl;
import org.tzi.use.uml.ocl.expr.operations.OpGeneric;
import org.tzi.use.uml.ocl.type.CollectionType;
import org.tzi.use.uml.ocl.type.EnumType;
import org.tzi.use.uml.ocl.type.TupleType;
import org.tzi.use.uml.ocl.type.Type;
import org.tzi.use.uml.ocl.type.Type.VoidHandling;
import org.tzi.use.uml.ocl.type.TypeFactory;

/**
 * Vorschläge für die Autovervollständigung in Modellen und Soil-Skripten. Was an
 * der Schreibmarke gebraucht wird (Schlüsselwort, Typ, Name, Element nach
 * {@code .}, {@code ->} oder {@code ::}), liest die Klasse aus dem Text davor;
 * den Typ des Ausdrucks vor dem Punkt bestimmt USE selbst, indem es ihn gegen
 * das zuletzt fehlerfrei übersetzte Modell übersetzt. Nicht threadsicher (USE
 * hat globalen Zustand); der Server ruft es nur aus seinem Prüf-Thread auf.
 */
final class UseCompletion {

    /** Ein Modell, aus dem Namen kommen: übersetzt (null, solange es keine fehlerfreie Fassung gab) und als Text. */
    record Model(MModel model, String text) {
    }

    private record Token(String text, int start, int end, int line) {
    }

    /** Namen aus den Modellen und ihrem Text; eine gerade erst getippte Klasse steht nur im Text. */
    private record Names(Set<String> classes, Map<String, List<String>> enums, Set<String> associations) {
    }

    /** Variablen (Typ null: unbekannt) für die Typbestimmung eines Ausdrucks. */
    private record Env(MModel model, MClass self, Map<String, Type> vars) {
    }

    /** Was der Text vor der Schreibmarke über die Stelle in einem Modell verrät. */
    private static final class Scope {
        String block; // null, "class", "association", "enum", "constraints"
        String section; // in Klassen: "attributes", "operations", "constraints", "statemachines"
        String selfClass;
        String selfVar;
        final Map<String, String> params = new LinkedHashMap<>();
        String resultType;
        boolean post;
        boolean operation;
        int exprStart = -1;

        void leaveDeclaration() {
            params.clear();
            resultType = null;
            post = false;
            operation = false;
            exprStart = -1;
        }
    }

    private static final String INPUT = "<completion>";

    // Sortierung: Variablen und Elemente zuerst, Schlüsselwörter zuletzt
    private static final String VARIABLES = "0";
    private static final String FEATURES = "1";
    private static final String STANDARD = "2";
    private static final String NAMES = "3";
    private static final String KEYWORDS = "4";
    private static final String STRUCTURE = "5";

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_]\\w*|\\d+|::|->|:=|\\.\\.|<>|<=|>=|\\S");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

    /** Stellen, an denen ein Typ steht: {@code name :} in Deklarationen, Parametern, Iteratoren, let. */
    private static final Pattern TYPE_POSITION = Pattern.compile(
            "(?:(?:^|[(,;])\\s*(?!(?:inv|pre|post|init|derive|derived)\\s*:)[A-Za-z_]\\w*\\s*:"
                    + "|\\)\\s*:|\\b(?:let|context|declare)\\s+[A-Za-z_]\\w*\\s*:"
                    + "|\\b(?:Set|Bag|Sequence|OrderedSet|Collection)\\s*\\()\\s*$");
    private static final Pattern SUPERCLASS = Pattern.compile("\\bclass\\s+[A-Za-z_]\\w*\\s*<\\s*(?:[\\w\\s,]*,\\s*)?$");
    private static final Pattern CONTEXT_CLASS = Pattern.compile("^\\s*context\\s+(?:[A-Za-z_]\\w*\\s*:\\s*)?$");
    private static final Pattern AFTER_ATTRIBUTE_TYPE = Pattern.compile("^\\s*[A-Za-z_]\\w*\\s*:\\s*[\\w()]+\\s+$");
    private static final Pattern CONTEXT_OPERATION = Pattern.compile("\\bcontext\\s+([A-Za-z_]\\w*)\\s*$");

    private static final Pattern ITERATOR = Pattern.compile(
            "->\\s*(select|reject|collect|collectNested|exists|forAll|isUnique|sortedBy|any|one|closure|iterate)\\s*\\(");
    private static final Pattern ITERATOR_VARS = Pattern.compile("\\s*([A-Za-z_]\\w*)\\s*(?::\\s*([^,|;]+?)\\s*)?"
            + "(?:,\\s*([A-Za-z_]\\w*)\\s*(?::\\s*([^,|;]+?)\\s*)?)?([|;])");
    private static final Pattern ACCUMULATOR = Pattern.compile("\\s*([A-Za-z_]\\w*)\\s*:\\s*([^=]+?)\\s*=");
    private static final Pattern LET = Pattern.compile("\\blet\\s+([A-Za-z_]\\w*)\\s*(?::\\s*([^=]+?)\\s*)?=");

    private static final Pattern CLASS_DECL = Pattern.compile(
            "\\b(?:class|associationclass|associationClass)\\s+([A-Za-z_]\\w*)");
    private static final Pattern ASSOCIATION_DECL = Pattern.compile(
            "\\b(?:association|composition|aggregation|associationclass|associationClass)\\s+([A-Za-z_]\\w*)");
    private static final Pattern ENUM_DECL = Pattern.compile("\\benum\\s+([A-Za-z_]\\w*)\\s*\\{([^}]*)");

    // Soil: Objekte des Skripts und die Stellen in einer Anweisung
    private static final Pattern CREATE = Pattern.compile("^\\s*!!?\\s*create\\s+([\\w\\s,]+?)\\s*:\\s*([A-Za-z_]\\w*)");
    private static final Pattern ASSIGN_NEW = Pattern.compile(
            "^\\s*!!?\\s*([A-Za-z_]\\w*)\\s*:=\\s*new\\s+([A-Za-z_]\\w*)(?:\\s*\\(\\s*'([^']*)')?");
    private static final Pattern NEW_NAMED = Pattern.compile("^\\s*!!?\\s*new\\s+([A-Za-z_]\\w*)\\s*\\(\\s*'([^']*)'");
    private static final Pattern DECLARE = Pattern.compile("^\\s*!!?\\s*declare\\s+(.*)$");
    private static final Pattern NEW_CLASS = Pattern.compile("(?:^|:=)\\s*new\\s+$|^\\s*create\\s+[\\w\\s,]*:\\s*$");
    private static final Pattern LINK_ASSOCIATION = Pattern.compile("^\\s*(?:insert|delete)\\s*\\(.*\\)\\s*(?:into|from)\\s+$");
    private static final Pattern LINK_OBJECTS = Pattern.compile("^\\s*(?:insert|delete)\\s*\\((?:[\\w\\s]*,)*\\s*$");
    private static final Pattern OBJECT_ARGUMENT = Pattern.compile("^\\s*(?:destroy\\s+(?:[\\w\\s]*,\\s*)*|openter\\s+|set\\s+)$");
    private static final Pattern OPENTER_OPERATION = Pattern.compile("^\\s*openter\\s+([A-Za-z_]\\w*)\\s+$");

    private static final List<String> OCL_KEYWORDS = List.of("and", "or", "xor", "implies", "not", "then", "else",
            "endif", "in", "true", "false", "null");
    private static final List<String> BUILTIN_TYPES = List.of("Integer", "Real", "Boolean", "String", "OclAny");
    private static final List<String> COLLECTION_KINDS = List.of("Set", "Bag", "Sequence", "OrderedSet");
    private static final List<String> ITERATORS = List.of("select", "reject", "collect", "collectNested", "exists",
            "forAll", "isUnique", "sortedBy", "any", "one", "closure");
    private static final List<String> SHELL_COMMANDS = List.of("check", "info class", "info model", "info state",
            "open", "reset", "undo", "redo", "help", "quit");
    private static final Set<String> HIDDEN_OPERATIONS = Set.of("useToId");
    /** Soil-Anweisungen, nach denen gleich ein Objekt oder eine Klasse kommt. */
    private static final Set<String> OBJECT_OR_CLASS_FOLLOWS = Set.of("new", "destroy", "set", "openter");

    private final Map<String, List<CompletionItem>> standardOperations = new HashMap<>();

    /**
     * Die Vorschläge an Zeile/Spalte (ab 0) von {@code text}. {@code trigger} ist das
     * Zeichen, das die Anfrage ausgelöst hat (null beim Tippen eines Namens);
     * {@code models} sind für ein Modell das Modell selbst, für ein Skript die
     * Modelle daneben.
     */
    List<CompletionItem> complete(String text, int line, int character, boolean soil, String trigger,
            List<Model> models) {
        int offset = offset(text, line, character);
        String code = blank(text.substring(0, offset));
        if (code == null) {
            return List.of();
        }
        int word = offset;
        while (word > 0 && isIdentChar(code.charAt(word - 1))) {
            word--;
        }
        String op = operator(code, word);
        if (trigger != null && word == offset && !fits(trigger, op, lineHead(code, word), soil)) {
            return List.of();
        }
        Items items = new Items();
        Names names = names(models);
        if (soil) {
            completeSoil(text, code, word, op, names, models, items);
        } else {
            completeModel(code, word, op, names, models.isEmpty() ? null : models.get(0).model(), items);
        }
        return items.list();
    }

    // ---------------------------------------------------------------- Modelle

    private void completeModel(String code, int word, String op, Names names, MModel model, Items items) {
        Scope s = scope(code, word);
        String head = lineHead(code, word);
        if (s.exprStart >= 0) {
            Env env = env(model, s, code, word);
            if (op != null) {
                members(op, code, word, s.exprStart, List.of(env), names, false, items);
            } else if (TYPE_POSITION.matcher(head).find()) {
                types(names, items);
            } else {
                expression(env, names, s.post, items);
                structure(s, head, items);
            }
            return;
        }
        if ("::".equals(op)) {
            Matcher m = CONTEXT_OPERATION.matcher(code.substring(0, word - 2));
            MClass c = model == null || !m.find() ? null : model.getClass(m.group(1));
            if (c != null) {
                for (MOperation o : c.allOperations()) {
                    String result = o.hasResultType() ? " : " + o.resultType() : "";
                    items.add(item(o.name(), CompletionItemKind.Method, "Operation von " + o.cls().name(), FEATURES)
                            .details("(" + o.paramList() + ")" + result, null)
                            .insert(o.name() + "(" + o.paramList() + ")" + result));
                }
            }
            return;
        }
        if (op != null) {
            return;
        }
        if (TYPE_POSITION.matcher(head).find()) {
            types(names, items);
        } else if (SUPERCLASS.matcher(head).find() || CONTEXT_CLASS.matcher(head).find()) {
            classes(names, items);
        } else {
            structure(s, head, items);
        }
    }

    /** Schlüsselwörter und Vorlagen für die Struktur eines Modells, je nach Abschnitt. */
    private static void structure(Scope s, String head, Items items) {
        if (!head.isBlank()) {
            if ("attributes".equals(s.section) && AFTER_ATTRIBUTE_TYPE.matcher(head).find()) {
                keywords(items, KEYWORDS, "init", "derive");
            }
            return;
        }
        if ("class".equals(s.block)) {
            keywords(items, STRUCTURE, "attributes", "operations", "constraints", "statemachines", "end");
            if ("operations".equals(s.section)) {
                keywords(items, STRUCTURE, "pre", "post", "begin");
            } else if ("constraints".equals(s.section)) {
                keywords(items, STRUCTURE, "inv");
            } else if ("statemachines".equals(s.section)) {
                keywords(items, STRUCTURE, "psm", "states", "transitions");
            }
        } else if ("association".equals(s.block)) {
            keywords(items, STRUCTURE, "role", "ordered", "subsets", "redefines", "union", "end");
        } else if (!"enum".equals(s.block)) {
            snippet(items, "context", "context … inv", "context ${1:Klasse} inv ${2:Name}:\n\t$0");
            snippet(items, "context", "context …::operation pre/post",
                    "context ${1:Klasse}::${2:operation}($3)\n\tpre ${4:Name}: $0");
            if ("constraints".equals(s.block)) {
                keywords(items, STRUCTURE, "inv", "pre", "post");
            }
            snippet(items, "class", "class … end", "class ${1:Name}\nattributes\n\t$0\nend");
            snippet(items, "abstract class", "abstract class … end", "abstract class ${1:Name}\nattributes\n\t$0\nend");
            snippet(items, "associationclass", "associationclass … between … end",
                    "associationclass ${1:Name} between\n\t${2:Klasse1} [${3:*}] role ${4:rolle1}\n"
                            + "\t${5:Klasse2} [${6:*}] role ${7:rolle2}\nattributes\n\t$0\nend");
            for (String kind : List.of("association", "composition", "aggregation")) {
                snippet(items, kind, kind + " … between … end", kind + " ${1:Name} between\n\t${2:Klasse1} [${3:*}] "
                        + "role ${4:rolle1}\n\t${5:Klasse2} [${6:*}] role ${7:rolle2}\nend");
            }
            snippet(items, "enum", "enum … { … }", "enum ${1:Name} { ${2:wert1}, ${3:wert2} }");
            snippet(items, "model", "model …", "model ${1:Name}\n\n$0");
            keywords(items, STRUCTURE, "constraints");
        }
    }

    /**
     * Liest Klasse, Abschnitt und den Ausdruck, in dem die Schreibmarke steht, aus
     * den Wörtern davor. Heuristisch, denn der Text ist beim Tippen oft unvollständig.
     */
    private static Scope scope(String code, int end) {
        List<Token> tokens = tokens(code, end);
        Scope s = new Scope();
        Deque<String> nesting = new ArrayDeque<>();
        String pending = null; // ":" bei inv/pre/post, ":=" bei init/derive (dort auch "=")
        int lastLine = -1;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            boolean first = t.line() != lastLine;
            lastLine = t.line();
            String w = t.text();
            String prev = i > 0 ? tokens.get(i - 1).text() : "";
            String next = i + 1 < tokens.size() ? tokens.get(i + 1).text() : "";
            if (prev.equals("@") || prev.equals(".") || prev.equals("->") || prev.equals("::")) {
                continue;
            }
            if (pending != null && (w.equals(":") || pending.equals(":=") && w.equals("="))) {
                s.exprStart = t.end();
                pending = null;
                continue;
            }
            switch (w) {
                case "model", "enum" -> {
                    s.block = w.equals("enum") ? "enum" : null;
                    s.section = null;
                    s.selfClass = null;
                    s.leaveDeclaration();
                }
                case "}" -> {
                    if ("enum".equals(s.block)) {
                        s.block = null;
                    }
                }
                case "class", "associationclass", "associationClass", "dataType", "signal" -> {
                    s.block = "class";
                    s.section = null;
                    s.selfClass = IDENT.matcher(next).matches() ? next : null;
                    s.selfVar = null;
                    s.leaveDeclaration();
                    nesting.clear();
                    pending = null;
                }
                case "association", "composition", "aggregation" -> {
                    s.block = "association";
                    s.selfClass = null;
                    s.leaveDeclaration();
                }
                case "attributes", "operations", "statemachines" -> {
                    if ("class".equals(s.block) && nesting.isEmpty()) {
                        s.section = w;
                        s.leaveDeclaration();
                        pending = null;
                    }
                }
                case "constraints" -> {
                    if ("class".equals(s.block) && nesting.isEmpty()) {
                        s.section = w;
                    } else {
                        s.block = "constraints";
                        s.section = null;
                        s.selfClass = null;
                    }
                    s.leaveDeclaration();
                    pending = null;
                }
                case "context" -> {
                    s.block = "constraints";
                    s.section = null;
                    s.leaveDeclaration();
                    pending = null;
                    i = contextHeader(tokens, i, s);
                }
                case "inv", "pre", "post" -> {
                    s.exprStart = -1;
                    s.post = w.equals("post");
                    pending = ":";
                }
                case "init", "derive", "derived" -> {
                    if ("attributes".equals(s.section)) {
                        s.exprStart = -1;
                        pending = ":=";
                    }
                }
                case "=" -> {
                    if ("operations".equals(s.section) && s.operation && s.exprStart < 0 && nesting.isEmpty()) {
                        s.exprStart = t.end();
                    }
                }
                case "begin" -> {
                    if (nesting.isEmpty()) {
                        s.exprStart = t.end();
                    }
                    nesting.push(w);
                }
                case "do", "psm", "bsm", "if" -> nesting.push(w);
                case "endif" -> {
                    if ("if".equals(nesting.peek())) {
                        nesting.pop();
                    }
                }
                case "end" -> {
                    if (!nesting.isEmpty()) {
                        if (nesting.pop().equals("begin") && nesting.isEmpty()) {
                            s.exprStart = -1;
                        }
                    } else if ("class".equals(s.block) || "association".equals(s.block)) {
                        s.block = null;
                        s.section = null;
                        s.selfClass = null;
                        s.selfVar = null;
                        s.leaveDeclaration();
                        pending = null;
                    }
                }
                default -> {
                    if (first && "class".equals(s.block) && nesting.isEmpty() && IDENT.matcher(w).matches()) {
                        if ("attributes".equals(s.section) && next.equals(":")) {
                            s.leaveDeclaration();
                            pending = null;
                        } else if ("operations".equals(s.section) && next.equals("(")) {
                            s.leaveDeclaration();
                            pending = null;
                            i = operationHeader(tokens, i + 1, s);
                        }
                    }
                }
            }
        }
        return s;
    }

    /** {@code context [v :] C [:: op(params) [: T]]}; liefert den Index des letzten gelesenen Worts. */
    private static int contextHeader(List<Token> tokens, int i, Scope s) {
        int j = i + 1;
        if (j + 2 < tokens.size() && tokens.get(j + 1).text().equals(":")) {
            s.selfVar = tokens.get(j).text();
            j += 2;
        } else {
            s.selfVar = null;
        }
        if (j >= tokens.size() || !IDENT.matcher(tokens.get(j).text()).matches()) {
            s.selfClass = null;
            return j - 1;
        }
        s.selfClass = tokens.get(j).text();
        if (j + 3 < tokens.size() && tokens.get(j + 1).text().equals("::") && tokens.get(j + 3).text().equals("(")) {
            return operationHeader(tokens, j + 3, s);
        }
        return j;
    }

    /** Parameter ab der öffnenden Klammer bei {@code i}, dann {@code : Ergebnistyp} auf derselben Zeile. */
    private static int operationHeader(List<Token> tokens, int i, Scope s) {
        s.operation = true;
        int depth = 0;
        String name = null;
        StringBuilder type = null;
        int j = i;
        for (; j < tokens.size(); j++) {
            String w = tokens.get(j).text();
            if (w.equals("(")) {
                depth++;
                if (depth == 1) {
                    continue;
                }
            } else if (w.equals(")")) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            if (depth == 1 && w.equals(",")) {
                param(s, name, type);
                name = null;
                type = null;
            } else if (depth == 1 && type == null && w.equals(":")) {
                type = new StringBuilder();
            } else if (type != null) {
                type.append(w);
            } else if (depth == 1 && IDENT.matcher(w).matches()) {
                name = w;
            }
        }
        param(s, name, type);
        if (j + 1 < tokens.size() && tokens.get(j + 1).text().equals(":")
                && tokens.get(j + 1).line() == tokens.get(j).line()) {
            StringBuilder result = new StringBuilder();
            int k = j + 2;
            while (k < tokens.size() && tokens.get(k).line() == tokens.get(j).line()
                    && !Set.of("=", "pre", "post", "begin").contains(tokens.get(k).text())) {
                result.append(tokens.get(k).text());
                k++;
            }
            s.resultType = result.isEmpty() ? null : result.toString();
            return k - 1;
        }
        return Math.min(j, tokens.size() - 1);
    }

    private static void param(Scope s, String name, StringBuilder type) {
        if (name != null) {
            s.params.put(name, type == null || type.isEmpty() ? null : type.toString());
        }
    }

    private Env env(MModel model, Scope s, String code, int word) {
        Map<String, Type> vars = new LinkedHashMap<>();
        MClass self = model == null || s.selfClass == null ? null : model.getClass(s.selfClass);
        if (s.selfClass != null) {
            vars.put("self", self);
            if (s.selfVar != null) {
                vars.put(s.selfVar, self);
            }
        }
        s.params.forEach((name, type) -> vars.put(name, typeNamed(model, type)));
        if (s.post && s.resultType != null) {
            vars.put("result", typeNamed(model, s.resultType));
        }
        Env env = new Env(model, self, vars);
        binders(code, s.exprStart, word, env);
        return env;
    }

    // ---------------------------------------------------------------- Soil

    private void completeSoil(String text, String code, int word, String op, Names names, List<Model> models,
            Items items) {
        int lineStart = code.lastIndexOf('\n', word - 1) + 1;
        String head = code.substring(lineStart, word);
        String command = head.stripLeading();
        Map<String, String> objects = objects(text);
        if (command.isEmpty()) {
            for (String c : SHELL_COMMANDS) {
                items.add(item(c, CompletionItemKind.Keyword, "Befehl der USE-Shell", KEYWORDS));
            }
            for (String s : List.of("new", "create", "insert", "delete", "destroy", "set", "openter", "opexit",
                    "declare")) {
                Item statement = item("!" + s, CompletionItemKind.Keyword, "Soil-Anweisung", KEYWORDS).filter(s)
                        .insert("!" + s + " ");
                items.add(OBJECT_OR_CLASS_FOLLOWS.contains(s) ? statement.retrigger() : statement);
            }
            return;
        }
        int marker = command.startsWith("!!") || command.startsWith("??") ? 2 : 1;
        int lower = lineStart + head.length() - command.length() + marker;
        List<Env> envs = soilEnvs(models, objects);
        for (Env env : envs) {
            binders(code, lower, word, env);
        }
        if (command.startsWith("!")) {
            String statement = command.substring(marker);
            Matcher m;
            if (op != null) {
                members(op, code, word, lower, envs, names, true, items);
            } else if (NEW_CLASS.matcher(statement).find()) {
                classes(names, items);
            } else if (LINK_ASSOCIATION.matcher(statement).find()) {
                for (String a : names.associations()) {
                    items.add(item(a, CompletionItemKind.Interface, "Assoziation", NAMES));
                }
            } else if (LINK_OBJECTS.matcher(statement).find() || OBJECT_ARGUMENT.matcher(statement).find()) {
                objectItems(objects, items);
            } else if ((m = OPENTER_OPERATION.matcher(statement)).find()) {
                MClass c = soilClass(envs, objects.get(m.group(1)));
                if (c != null) {
                    for (MOperation o : c.allOperations()) {
                        items.add(operationItem(o));
                    }
                }
            } else if (TYPE_POSITION.matcher(statement).find()) {
                types(names, items);
            } else if (statement.isBlank()) {
                for (String s : OBJECT_OR_CLASS_FOLLOWS) {
                    items.add(item(s, CompletionItemKind.Keyword, null, KEYWORDS).insert(s + " ").retrigger());
                }
                keywords(items, KEYWORDS, "opexit", "if", "for", "while", "begin");
                snippet(items, "create", "create … : …", "create ${1:name} : ${2:Klasse}");
                snippet(items, "insert", "insert (…) into …", "insert (${1:a}, ${2:b}) into ${3:Assoziation}");
                snippet(items, "delete", "delete (…) from …", "delete (${1:a}, ${2:b}) from ${3:Assoziation}");
                snippet(items, "declare", "declare … : …", "declare ${1:name} : ${2:Typ}");
                objectItems(objects, items);
            } else {
                expression(envs.isEmpty() ? new Env(null, null, Map.of()) : envs.get(0), names, false, items);
                objectItems(objects, items);
            }
        } else if (command.startsWith("?") || command.startsWith(":")) {
            if (op != null) {
                members(op, code, word, lower, envs, names, false, items);
            } else {
                expression(envs.isEmpty() ? new Env(null, null, Map.of()) : envs.get(0), names, false, items);
                objectItems(objects, items);
            }
        }
    }

    /** Objekte und Variablen des Skripts mit ihrer Klasse (bzw. ihrem Typ als Text). */
    private static Map<String, String> objects(String text) {
        Map<String, String> objects = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            Matcher m;
            if ((m = CREATE.matcher(line)).find()) {
                for (String name : m.group(1).split(",")) {
                    if (IDENT.matcher(name.strip()).matches()) {
                        objects.put(name.strip(), m.group(2));
                    }
                }
            } else if ((m = ASSIGN_NEW.matcher(line)).find()) {
                objects.put(m.group(1), m.group(2));
                if (m.group(3) != null && IDENT.matcher(m.group(3)).matches()) {
                    objects.put(m.group(3), m.group(2));
                }
            } else if ((m = NEW_NAMED.matcher(line)).find()) {
                if (IDENT.matcher(m.group(2)).matches()) {
                    objects.put(m.group(2), m.group(1));
                }
            } else if ((m = DECLARE.matcher(line)).find()) {
                for (String decl : m.group(1).split(",(?![^(]*\\))")) {
                    String[] parts = decl.split(":", 2);
                    if (parts.length == 2 && IDENT.matcher(parts[0].strip()).matches()) {
                        objects.put(parts[0].strip(), parts[1].strip());
                    }
                }
            }
        }
        return objects;
    }

    /** Je übersetztem Modell die Objekte des Skripts mit ihrem Typ in diesem Modell. */
    private List<Env> soilEnvs(List<Model> models, Map<String, String> objects) {
        List<Env> envs = new ArrayList<>();
        for (Model model : models) {
            if (model.model() != null) {
                Map<String, Type> vars = new LinkedHashMap<>();
                objects.forEach((name, type) -> vars.put(name, typeNamed(model.model(), type)));
                envs.add(new Env(model.model(), null, vars));
            }
        }
        return envs;
    }

    private static MClass soilClass(List<Env> envs, String name) {
        for (Env env : envs) {
            if (name != null && env.model().getClass(name) != null) {
                return env.model().getClass(name);
            }
        }
        return null;
    }

    private static void objectItems(Map<String, String> objects, Items items) {
        objects.forEach((name, type) -> items.add(item(name, CompletionItemKind.Variable, type, VARIABLES)
                .details(null, type)));
    }

    // ---------------------------------------------------------------- Ausdrücke

    /** Namen in einem Ausdruck: Variablen, Elemente von self, Klassen, Aufzählungen, Schlüsselwörter. */
    private static void expression(Env env, Names names, boolean post, Items items) {
        env.vars().forEach((name, type) -> items.add(item(name, CompletionItemKind.Variable,
                type == null ? null : type.toString(), VARIABLES).details(null, type == null ? null : type.toString())));
        if (env.self() != null) {
            features(env.self(), false, items);
        }
        if (post) {
            keywords(items, KEYWORDS, "result");
        }
        classesAndEnums(names, items);
        keywords(items, KEYWORDS, OCL_KEYWORDS.toArray(String[]::new));
        snippet(items, "if", "if … then … else … endif", "if $1 then $2 else $3 endif");
        snippet(items, "let", "let … = … in", "let ${1:x} = $2 in $0");
        for (String kind : COLLECTION_KINDS) {
            snippet(items, kind + "{}", kind + "{…}", kind + "{$1}");
        }
        snippet(items, "Tuple{}", "Tuple{…}", "Tuple{${1:name} = $2}");
    }

    /** Vorschläge nach {@code .}, {@code ->} oder {@code ::}, mit dem Typ des Ausdrucks davor. */
    private void members(String op, String code, int word, int lower, List<Env> envs, Names names, boolean statement,
            Items items) {
        int end = word - op.length();
        String receiver = code.substring(chainStart(code, end, lower), end).strip();
        if (receiver.isEmpty()) {
            return;
        }
        if (op.equals("::")) {
            for (String literal : names.enums().getOrDefault(receiver, List.of())) {
                items.add(item(literal, CompletionItemKind.EnumMember, receiver, FEATURES));
            }
            return;
        }
        if (op.equals(".") && receiver.chars().allMatch(Character::isDigit)) {
            return;
        }
        boolean variable = envs.stream().anyMatch(e -> e.vars().containsKey(receiver));
        if (op.equals(".") && names.classes().contains(receiver) && !variable) {
            items.add(item("allInstances", CompletionItemKind.Method, "alle Objekte von " + receiver, FEATURES)
                    .details("()", "Set(" + receiver + ")").insert("allInstances()"));
            return;
        }
        for (Env env : envs) {
            Type t = typeOf(receiver, env);
            if (t != null) {
                if (op.equals("->")) {
                    collectionMembers(t, items);
                } else {
                    objectMembers(t, statement, items);
                }
                return;
            }
        }
    }

    private void objectMembers(Type t, boolean statement, Items items) {
        // auf einer Collection ist x.a die Kurzform von x->collect(a)
        Type target = t instanceof CollectionType c ? c.elemType() : t;
        if (target instanceof MClass c) {
            features(c, statement, items);
        } else if (target instanceof TupleType tuple) {
            for (TupleType.Part part : tuple.getParts().values()) {
                items.add(item(part.name(), CompletionItemKind.Field, "Teil von " + tuple, FEATURES)
                        .details(null, part.type().toString()));
            }
        }
        items.addAll(standard(target, false));
        for (String cast : List.of("oclIsKindOf", "oclIsTypeOf", "oclAsType")) {
            items.add(item(cast, CompletionItemKind.Function, "Standardoperation", STANDARD).details("(Typ)", null)
                    .insert(cast + "(${1:Typ})").snippet());
        }
    }

    private void collectionMembers(Type t, Items items) {
        Type collection = t instanceof CollectionType ? t : TypeFactory.mkSet(t);
        items.addAll(standard(collection, true));
        Type elem = ((CollectionType) collection).elemType();
        String var = elem instanceof MClass c ? c.name().substring(0, 1).toLowerCase(Locale.ROOT) : "e";
        for (String it : ITERATORS) {
            items.add(item(it, CompletionItemKind.Function, "Iterator", STANDARD).details("(" + var + " | …)", null)
                    .insert(it + "(${1:" + var + "} | $0)").snippet());
        }
        items.add(item("iterate", CompletionItemKind.Function, "Iterator", STANDARD)
                .details("(" + var + "; acc : T = … | …)", null)
                .insert("iterate(${1:" + var + "}; ${2:acc} : ${3:Integer} = ${4:0} | $0)").snippet());
        for (String select : List.of("selectByKind", "selectByType")) {
            items.add(item(select, CompletionItemKind.Function, "Standardoperation", STANDARD).details("(Typ)", null)
                    .insert(select + "(${1:Typ})").snippet());
        }
    }

    /** Attribute, Rollen und Operationen einer Klasse (mit geerbten); Operationen ohne Ergebnis nur in Anweisungen. */
    private static void features(MClass c, boolean statement, Items items) {
        for (MAttribute a : c.allAttributes()) {
            items.add(item(a.name(), CompletionItemKind.Field, "Attribut von " + a.owner().name(), FEATURES)
                    .details(null, a.type().toString()));
        }
        Set<String> roles = new TreeSet<>();
        List<MClass> hierarchy = new ArrayList<>();
        hierarchy.add(c);
        hierarchy.addAll(c.allParents());
        for (MClass k : hierarchy) {
            for (MNavigableElement end : k.navigableEnds().values()) {
                if (roles.add(end.nameAsRolename())) {
                    String type;
                    try {
                        type = end.getType(k, end, false).toString();
                    } catch (RuntimeException e) {
                        type = end.cls().name();
                    }
                    MAssociation a = end.association();
                    items.add(item(end.nameAsRolename(), CompletionItemKind.Property,
                            "Rolle" + (a == null ? "" : " in " + a.name()), FEATURES).details(null, type));
                }
            }
        }
        for (MOperation o : c.allOperations()) {
            if (statement || o.isCallableFromOCL()) {
                items.add(operationItem(o));
            }
        }
    }

    private static Item operationItem(MOperation o) {
        StringBuilder insert = new StringBuilder(o.name()).append('(');
        int n = 0;
        for (VarDecl p : o.paramList()) {
            insert.append(n > 0 ? ", " : "").append("${").append(++n).append(':').append(p.name()).append('}');
        }
        insert.append(')');
        return item(o.name(), CompletionItemKind.Method, "Operation von " + o.cls().name(), FEATURES)
                .details("(" + o.paramList() + ")", o.hasResultType() ? o.resultType().toString() : null)
                .insert(insert.toString()).snippet();
    }

    /**
     * Die Standardoperationen von USE, die auf einen Typ passen. Welche das sind,
     * entscheidet USE selbst: jede Operation wird mit dem Typ und möglichen
     * Argumenttypen probiert.
     */
    private List<CompletionItem> standard(Type t, boolean collection) {
        return standardOperations.computeIfAbsent(t.toString(), key -> {
            List<Type> candidates = new ArrayList<>();
            if (t instanceof CollectionType c) {
                candidates.add(c.elemType());
            }
            candidates.addAll(List.of(t, TypeFactory.mkInteger(), TypeFactory.mkReal(), TypeFactory.mkString(),
                    TypeFactory.mkBoolean(), TypeFactory.mkOclAny()));
            Items found = new Items();
            for (Map.Entry<String, OpGeneric> e : ExpStdOp.opmap.entries()) {
                OpGeneric op = e.getValue();
                if (op.isInfixOrPrefix() || HIDDEN_OPERATIONS.contains(e.getKey())) {
                    continue;
                }
                Type[] match = match(op, t, candidates);
                if (match != null) {
                    StringBuilder params = new StringBuilder("(");
                    StringBuilder insert = new StringBuilder(e.getKey()).append('(');
                    for (int i = 1; i < match.length - 1; i++) {
                        params.append(i > 1 ? ", " : "").append(match[i]);
                        insert.append(i > 1 ? ", " : "").append("${").append(i).append('}');
                    }
                    found.add(item(e.getKey(), CompletionItemKind.Function, "Standardoperation", STANDARD)
                            .details(params.append(')').toString(), match[match.length - 1].toString())
                            .insert(insert.append(')').toString()).snippet());
                }
            }
            return found.list();
        });
    }

    /** Argumenttypen (bis zwei), mit denen {@code op} auf {@code t} passt; zuletzt der Ergebnistyp. */
    private static Type[] match(OpGeneric op, Type t, List<Type> candidates) {
        try {
            Type result = op.matches(new Type[] {t});
            if (result != null) {
                return new Type[] {t, result};
            }
            for (Type a : candidates) {
                if ((result = op.matches(new Type[] {t, a})) != null) {
                    return new Type[] {t, a, result};
                }
            }
            for (Type a : candidates) {
                for (Type b : candidates) {
                    if ((result = op.matches(new Type[] {t, a, b})) != null) {
                        return new Type[] {t, a, b, result};
                    }
                }
            }
        } catch (RuntimeException e) {
            // manche Operationen prüfen ihre Argumente nicht vollständig
        }
        return null;
    }

    /** Iteratorvariablen der offenen Iteratoren und let-Variablen vor der Schreibmarke. */
    private void binders(String code, int start, int end, Env env) {
        String expr = code.substring(start, end);
        Matcher it = ITERATOR.matcher(expr);
        Matcher let = LET.matcher(expr);
        int from = 0;
        while (true) {
            boolean isIt = it.find(from);
            int itAt = isIt ? it.start() : Integer.MAX_VALUE;
            boolean isLet = let.find(from);
            int letAt = isLet ? let.start() : Integer.MAX_VALUE;
            if (!isIt && !isLet) {
                return;
            }
            if (itAt < letAt) {
                from = it.end();
                if (closes(expr, it.end(), '(', ')')) {
                    continue;
                }
                Matcher vars = ITERATOR_VARS.matcher(expr).region(it.end(), expr.length());
                if (!vars.lookingAt()) {
                    continue;
                }
                Type source = typeOf(expr.substring(chainStart(expr, itAt, 0), itAt), env);
                Type elem = source instanceof CollectionType c ? c.elemType() : source;
                bind(env, vars.group(1), vars.group(2), elem);
                bind(env, vars.group(3), vars.group(4), elem);
                Matcher acc;
                if (vars.group(5).equals(";")
                        && (acc = ACCUMULATOR.matcher(expr).region(vars.end(), expr.length())).lookingAt()) {
                    bind(env, acc.group(1), acc.group(2), null);
                }
            } else {
                from = let.end();
                int in = keywordAt(expr, let.end(), "in");
                if (in < 0) {
                    continue;
                }
                Type init = let.group(2) == null ? typeOf(expr.substring(let.end(), in), env) : null;
                bind(env, let.group(1), let.group(2), init);
            }
        }
    }

    private void bind(Env env, String name, String typeText, Type type) {
        if (name != null) {
            env.vars().put(name, typeText != null ? typeNamed(env.model(), typeText.strip()) : type);
        }
    }

    /** Ob die Klammer vor {@code from} vor dem Ende von {@code s} wieder geschlossen wird. */
    private static boolean closes(String s, int from, char open, char close) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close && --depth == 0) {
                return true;
            }
        }
        return false;
    }

    /** Das nächste Schlüsselwort {@code word} außerhalb von Klammern ab {@code from}, sonst -1. */
    private static int keywordAt(String s, int from, String word) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (depth == 0 && s.startsWith(word, i) && (i == 0 || !isIdentChar(s.charAt(i - 1)))
                    && (i + word.length() == s.length() || !isIdentChar(s.charAt(i + word.length())))) {
                return i;
            }
        }
        return -1;
    }

    /** Der Typ eines Ausdrucks, übersetzt von USE; null, wenn USE ihn nicht übersetzt. */
    private Type typeOf(String expr, Env env) {
        String e = expr.replace("@pre", "").strip();
        if (e.isEmpty() || env.model() == null) {
            return null;
        }
        // USE erlaubt self wegzulassen: aus "medium" wird "self.medium"
        Matcher first = IDENT.matcher(e);
        if (env.self() != null && first.lookingAt() && !env.vars().containsKey(first.group())
                && hasFeature(env.self(), first.group())) {
            e = "self." + e;
        }
        Symtable vars = new Symtable();
        for (Map.Entry<String, Type> v : env.vars().entrySet()) {
            if (v.getValue() != null) {
                try {
                    vars.add(v.getKey(), v.getValue(), null);
                } catch (SemanticException | RuntimeException ex) {
                    // doppelt deklariert: die erste gilt
                }
            }
        }
        String input = e;
        Expression[] result = new Expression[1];
        Capture.during(() -> {
            try {
                result[0] = OCLCompiler.compileExpression(env.model(), input, INPUT,
                        new PrintWriter(new StringWriter()), vars);
            } catch (RuntimeException ex) {
                result[0] = null;
            }
        });
        return result[0] == null ? null : result[0].type();
    }

    private static boolean hasFeature(MClass c, String name) {
        if (c.attribute(name, true) != null || c.operation(name, true) != null || c.navigableEnd(name) != null) {
            return true;
        }
        return c.allParents().stream().anyMatch(p -> p.navigableEnd(name) != null);
    }

    private static Type typeNamed(MModel model, String text) {
        if (model == null || text == null || text.isBlank()) {
            return null;
        }
        Type[] type = new Type[1];
        Capture.during(() -> {
            try {
                type[0] = OCLCompiler.compileType(model, text, INPUT, new PrintWriter(new StringWriter()));
            } catch (RuntimeException e) {
                type[0] = null;
            }
        });
        return type[0];
    }

    /**
     * Der Anfang des Ausdrucks, der bei {@code end} endet: Namen, Punkte, Pfeile und
     * Klammern rückwärts, höchstens bis {@code lower}.
     */
    static int chainStart(String s, int end, int lower) {
        int i = end;
        while (i > lower && Character.isWhitespace(s.charAt(i - 1))) {
            i--;
        }
        boolean afterOperator = true;
        while (i > lower) {
            char c = s.charAt(i - 1);
            if (isIdentChar(c)) {
                i--;
                afterOperator = false;
            } else if (c == ')' || c == ']' || c == '}') {
                int open = opening(s, i - 1, lower);
                if (open < 0) {
                    break;
                }
                i = open;
                afterOperator = false;
            } else if (c == '\'') {
                int open = s.lastIndexOf('\'', i - 2);
                if (open < lower) {
                    break;
                }
                i = open;
                afterOperator = false;
            } else if (c == '.' || c == '@') {
                i--;
                afterOperator = true;
            } else if ((c == '>' && i - 2 >= lower && s.charAt(i - 2) == '-')
                    || (c == ':' && i - 2 >= lower && s.charAt(i - 2) == ':')) {
                i -= 2;
                afterOperator = true;
            } else if (Character.isWhitespace(c)) {
                // Leerraum nur vor oder nach einem Punkt oder Pfeil, etwa am Zeilenanfang
                int k = i - 1;
                while (k > lower && Character.isWhitespace(s.charAt(k - 1))) {
                    k--;
                }
                boolean beforeOperator = s.charAt(i) == '.' || s.startsWith("->", i);
                boolean operatorBefore = k > lower && (s.charAt(k - 1) == '.' || s.startsWith("->", k - 2));
                if (!(afterOperator || beforeOperator || operatorBefore) || k == lower) {
                    break;
                }
                i = k;
            } else {
                break;
            }
        }
        return i;
    }

    private static int opening(String s, int close, int lower) {
        char closing = s.charAt(close);
        char open = closing == ')' ? '(' : closing == ']' ? '[' : '{';
        int depth = 0;
        for (int i = close; i >= lower; i--) {
            char c = s.charAt(i);
            if (c == closing) {
                depth++;
            } else if (c == open && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- Namen und Typen

    private static Names names(List<Model> models) {
        Set<String> classes = new TreeSet<>();
        Map<String, List<String>> enums = new LinkedHashMap<>();
        Set<String> associations = new TreeSet<>();
        for (Model m : models) {
            if (m.model() != null) {
                m.model().classes().forEach(c -> classes.add(c.name()));
                m.model().associations().forEach(a -> associations.add(a.name()));
                for (EnumType e : m.model().enumTypes()) {
                    enums.put(e.name(), e.getLiterals());
                }
            }
            String code = blank(m.text() + "\n");
            if (code == null) {
                continue;
            }
            Matcher c = CLASS_DECL.matcher(code);
            while (c.find()) {
                classes.add(c.group(1));
            }
            Matcher a = ASSOCIATION_DECL.matcher(code);
            while (a.find()) {
                associations.add(a.group(1));
            }
            Matcher e = ENUM_DECL.matcher(code);
            while (e.find()) {
                List<String> literals = new ArrayList<>();
                Matcher l = IDENT.matcher(e.group(2));
                while (l.find()) {
                    literals.add(l.group());
                }
                enums.putIfAbsent(e.group(1), literals);
            }
        }
        return new Names(classes, enums, associations);
    }

    private static void classes(Names names, Items items) {
        for (String c : names.classes()) {
            items.add(item(c, CompletionItemKind.Class, "Klasse", NAMES));
        }
    }

    private static void classesAndEnums(Names names, Items items) {
        classes(names, items);
        for (String e : names.enums().keySet()) {
            items.add(item(e, CompletionItemKind.Enum, "Aufzählung", NAMES));
        }
    }

    private static void types(Names names, Items items) {
        for (String t : BUILTIN_TYPES) {
            items.add(item(t, CompletionItemKind.TypeParameter, "Grundtyp", NAMES));
        }
        for (String kind : COLLECTION_KINDS) {
            items.add(item(kind + "()", CompletionItemKind.TypeParameter, "Collection-Typ", NAMES).filter(kind)
                    .insert(kind + "(${1:Typ})").snippet());
        }
        items.add(item("Tuple()", CompletionItemKind.TypeParameter, "Tupeltyp", NAMES).filter("Tuple")
                .insert("Tuple(${1:name} : ${2:Typ})").snippet());
        classesAndEnums(names, items);
    }

    // ---------------------------------------------------------------- Text

    /** Der Text mit Leerzeichen statt Kommentaren und Zeichenketten; null, wenn sein Ende in einem davon liegt. */
    static String blank(String text) {
        StringBuilder b = new StringBuilder(text);
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            char d = i + 1 < n ? text.charAt(i + 1) : 0;
            if (c == '-' && d == '-' || c == '/' && d == '/') {
                int e = text.indexOf('\n', i);
                if (e < 0) {
                    return null;
                }
                fill(b, i, e);
                i = e;
            } else if (c == '/' && d == '*') {
                int e = text.indexOf("*/", i + 2);
                if (e < 0) {
                    return null;
                }
                fill(b, i, e + 2);
                i = e + 2;
            } else if (c == '\'') {
                int e = i + 1;
                while (e < n && text.charAt(e) != '\'' && text.charAt(e) != '\n') {
                    e += text.charAt(e) == '\\' ? 2 : 1;
                }
                if (e >= n) {
                    return null;
                }
                fill(b, i + 1, e);
                i = e + 1;
            } else {
                i++;
            }
        }
        return b.toString();
    }

    private static void fill(StringBuilder b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b.charAt(i) != '\n') {
                b.setCharAt(i, ' ');
            }
        }
    }

    private static List<Token> tokens(String code, int end) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(code).region(0, end);
        int line = 0;
        int counted = 0;
        while (m.find()) {
            for (; counted < m.start(); counted++) {
                if (code.charAt(counted) == '\n') {
                    line++;
                }
            }
            tokens.add(new Token(m.group(), m.start(), m.end(), line));
        }
        return tokens;
    }

    private static String operator(String code, int word) {
        if (word >= 1 && code.charAt(word - 1) == '.') {
            return word >= 2 && code.charAt(word - 2) == '.' ? null : ".";
        }
        if (word >= 2 && code.startsWith("->", word - 2)) {
            return "->";
        }
        if (word >= 2 && code.startsWith("::", word - 2)) {
            return "::";
        }
        return null;
    }

    /** Ob das auslösende Zeichen hier etwas zu vervollständigen beginnt (">" nur in "->", ":" nur in "::"). */
    private static boolean fits(String trigger, String op, String head, boolean soil) {
        return switch (trigger) {
            case "." -> ".".equals(op);
            case ">" -> "->".equals(op);
            case ":" -> "::".equals(op);
            case "!" -> soil && (head.strip().equals("!") || head.strip().equals("!!"));
            default -> true;
        };
    }

    private static String lineHead(String code, int word) {
        return code.substring(code.lastIndexOf('\n', word - 1) + 1, word);
    }

    private static int offset(String text, int line, int character) {
        int offset = 0;
        for (int l = 0; l < line; l++) {
            int nl = text.indexOf('\n', offset);
            if (nl < 0) {
                return text.length();
            }
            offset = nl + 1;
        }
        int lineEnd = text.indexOf('\n', offset);
        int max = lineEnd < 0 ? text.length() : lineEnd;
        return Math.min(offset + character, max);
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ---------------------------------------------------------------- Vorschläge

    private static Item item(String label, CompletionItemKind kind, String detail, String group) {
        return new Item(label, kind, detail, group);
    }

    private static void keywords(Items items, String group, String... words) {
        for (String w : words) {
            items.add(item(w, CompletionItemKind.Keyword, null, group));
        }
    }

    private static void snippet(Items items, String label, String detail, String body) {
        items.add(item(label, CompletionItemKind.Snippet, detail, STRUCTURE).details(null, detail).insert(body)
                .snippet());
    }

    /** Ein Vorschlag im Aufbau. */
    private static final class Item {
        final CompletionItem c = new CompletionItem();

        Item(String label, CompletionItemKind kind, String detail, String group) {
            c.setLabel(label);
            c.setKind(kind);
            c.setDetail(detail);
            c.setSortText(group + label);
        }

        Item details(String parameters, String type) {
            CompletionItemLabelDetails d = new CompletionItemLabelDetails();
            d.setDetail(parameters);
            d.setDescription(type);
            c.setLabelDetails(d);
            return this;
        }

        Item insert(String text) {
            c.setInsertText(text);
            return this;
        }

        Item snippet() {
            c.setInsertTextFormat(InsertTextFormat.Snippet);
            return this;
        }

        Item filter(String text) {
            c.setFilterText(text);
            return this;
        }

        /** Öffnet nach dem Einfügen gleich die nächsten Vorschläge (etwa Klassen nach "!new "). */
        Item retrigger() {
            c.setCommand(new Command("", "editor.action.triggerSuggest"));
            return this;
        }
    }

    /** Vorschläge ohne Doppelte (gleicher Name und gleiche Parameter). */
    private static final class Items {
        private final Map<String, CompletionItem> items = new LinkedHashMap<>();

        void add(Item item) {
            CompletionItemLabelDetails d = item.c.getLabelDetails();
            items.putIfAbsent(item.c.getLabel() + (d == null ? "" : d.getDetail()), item.c);
        }

        void addAll(List<CompletionItem> list) {
            for (CompletionItem c : list) {
                CompletionItemLabelDetails d = c.getLabelDetails();
                items.putIfAbsent(c.getLabel() + (d == null ? "" : d.getDetail()), c);
            }
        }

        List<CompletionItem> list() {
            return new ArrayList<>(items.values());
        }
    }
}
