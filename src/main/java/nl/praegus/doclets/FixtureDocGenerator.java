package nl.praegus.doclets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.javadoc.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This doclet generates json files containing javadoc information.
 */
public class FixtureDocGenerator {

    private static final Set<String> METHODS_TO_IGNORE;

    static {
        METHODS_TO_IGNORE = new HashSet<>();
        METHODS_TO_IGNORE.add("toString");
        METHODS_TO_IGNORE.add("aroundSlimInvoke");
        METHODS_TO_IGNORE.add("getClass");
        METHODS_TO_IGNORE.add("equals");
        METHODS_TO_IGNORE.add("notify");
        METHODS_TO_IGNORE.add("notifyAll");
        METHODS_TO_IGNORE.add("wait");
        METHODS_TO_IGNORE.add("hashCode");
    }

    /**
     * PATTERN_USAGE contains the regex pattern used to filter usage strings (wiki text)
     */
    private static Pattern PATTERN_USAGE = Pattern.compile(".*[U|u]sage:\\s(\\|[\\w\\s|()\\[\\]\\\\]+\\|).*", Pattern.DOTALL);

    public static boolean start(RootDoc root) throws Exception {
        ClassDoc[] classes = root.classes();

        for (ClassDoc c : classes) {
            parseClass(c);
        }
        return true;
    }

    private static void parseClass(ClassDoc c) throws Exception {

        HashMap<String, Object> props = new HashMap<>();
        props.put("typeName", c.typeName());
        props.put("name", c.name());
        props.put("qualifiedName", c.qualifiedName());

        ArrayList<Object> annotations = new ArrayList<>();
        for (AnnotationDesc annotation : c.annotations()) {
            annotations.add(annotation.annotationType().name());
        }
        props.put("annotations", annotations);

        ArrayList<Object> constructors = new ArrayList<>();
        for (ConstructorDoc constructor : c.constructors()) {
            if(constructor.isPublic()) {
                constructors.add(parseConstructor(constructor));
            }
        }
        props.put("constructors", constructors);

        ArrayList<Object> methods = new ArrayList<>();
        collectAllPublicMethods(c, methods);
        props.put("publicMethods", methods);

        writeJson(new File("." + File.separator + c.qualifiedName() + ".json"), props);

        for (ClassDoc ic : c.innerClasses()) {
            parseClass(ic);
        }
    }

    private static void collectAllPublicMethods(ClassDoc c, ArrayList<Object> methods) {
        for (MethodDoc method : c.methods()) {
            if (method.isPublic() && !METHODS_TO_IGNORE.contains(method.name())) {
                methods.add(parseMethod(method));
            }
        }
        if (null != c.superclassType()) {
            collectAllPublicMethods(c.superclass(), methods);
        }
    }

    private static Object parseMethod(MethodDoc method) {
        HashMap<String, Object> properties = new HashMap<>();
        String docString = method.commentText();
        if(method.tags("@deprecated").length > 0) {
            String lineBreak = docString.length() > 0 ? "\r\n" : "";
            docString += lineBreak + "<b>Deprecated:</b> " + method.tags("@deprecated")[0].text();
        }
        properties.put("name", method.name());
        properties.put("readableName", splitCamelCase(method.name()));
        properties.put("docString", docString);

        ArrayList<HashMap<String, Object>> parameters = new ArrayList<>();
        for (Parameter parameter : method.parameters()) {
            parameters.add(parseParameter(parameter, method.paramTags()));
        }
        properties.put("parameters", parameters);

        ArrayList<Object> exceptions = new ArrayList<>();
        for (Type exception : method.thrownExceptionTypes()) {
            exceptions.add(exception.typeName());
        }
        properties.put("exceptions", exceptions);

        ArrayList<Object> annotations = new ArrayList<>();
        for (AnnotationDesc annotation : method.annotations()) {
            annotations.add(annotation.annotationType().name());
        }
        properties.put("annotations", annotations);

        properties.put("returnType", method.returnType().typeName());
        if(method.tags("@return").length > 0) {
            properties.put("returnDescription", method.tags("@return")[0].text());
        }

        Matcher matcher = PATTERN_USAGE.matcher(method.commentText());
        String usage = matcher.matches() ? matcher.group(1) : generateMethodUsageString(method);

        properties.put("usage", usage);
        String contextHelp = usage.substring(2)
                            .replaceAll("\\| \\[(\\w+)] \\|", "&lt;$1&gt;")
                            .replace("|", "")
                            .trim();
        properties.put("contexthelp", contextHelp);

        return properties;
    }

    private static Object parseConstructor(ConstructorDoc constructor) {
        HashMap<String, Object> properties = new HashMap<>();
        properties.put("name", constructor.name());
        properties.put("readableName", splitCamelCase(constructor.name()));
        properties.put("docString", constructor.commentText());

        ArrayList<Object> exceptions = new ArrayList<>();
        for (Type exception : constructor.thrownExceptionTypes()) {
            exceptions.add(exception.typeName());
        }
        properties.put("exceptions", exceptions);

        ArrayList<Object> annotations = new ArrayList<>();
        for (AnnotationDesc annotation : constructor.annotations()) {
            annotations.add(annotation.annotationType().name());
        }
        properties.put("annotations", annotations);

        ArrayList<Object> parameters = new ArrayList<>();
        for (Parameter parameter : constructor.parameters()) {
            parameters.add(parseParameter(parameter, constructor.paramTags()));
        }
        properties.put("parameters", parameters);


        Matcher matcher = PATTERN_USAGE.matcher(constructor.commentText());
        if (matcher.matches()) {
            properties.put("usage", matcher.group(1));
        } else {
            properties.put("usage", generateContructorUsageString(constructor));
        }

        return properties;
    }

    private static HashMap<String, Object> parseParameter(Parameter parameter, ParamTag[] paramDocs) {
        HashMap<String, Object> properties = new HashMap<>();
        properties.put("name", parameter.name());
        properties.put("type", parameter.type().typeName());
        for(ParamTag doc : paramDocs) {
            if(doc.parameterName().equals(parameter.name())) {
                properties.put("description", doc.parameterComment());
                break;
            }
        }
        return properties;
    }

    private static void writeJson(File f, Object o) throws IOException {
        if (f.exists()) {
            f.delete();
        }
        if (!f.createNewFile()) {
            throw new IOException("Can't create file " + f.getName());
        }
        if (!f.canWrite()) {
            throw new IOException("Can't write to " + f.getName());
        }

        FileWriter fw = new FileWriter(f);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(o);
        fw.write(json);
        fw.flush();
        fw.close();
    }

    private static String splitCamelCase(String s) {
        return s.replaceAll(
                String.format("%s|%s|%s",
                        "(?<=[A-Z])(?=[A-Z][a-z])",
                        "(?<=[^A-Z])(?=[A-Z])",
                        "(?<=[A-Za-z])(?=[^A-Za-z])"
                ),
                " "
        ).toLowerCase();
    }

    private static String generateContructorUsageString(ConstructorDoc constructor) {
        StringBuilder wikiText = new StringBuilder("| ");
        wikiText.append(splitCamelCase(constructor.name()))
                .append(" |");
        for (Parameter parameter : constructor.parameters()) {
            String paramDisplay = String.format(" [%s]", parameter.name());
                        wikiText.append(paramDisplay)
                    .append(" |");
        }
        return wikiText.toString();
    }

    private static String generateMethodUsageString(MethodDoc method) {
        String readableMethodName =splitCamelCase(method.name());
        String[] methodNameParts = readableMethodName.split(" ");

        int numberOfParts = methodNameParts.length;
        int numberOfParams = method.parameters().length;

        StringBuilder result = new StringBuilder("| ");

        if (numberOfParams > numberOfParts) {
            result.append(readableMethodName)
                    .append(" | ");
            for (Parameter parameter : method.parameters()) {
                result.append(parameter.name())
                        .append(", ");
            }
            result.append(" |");
        } else {
            int totalCells = numberOfParts + numberOfParams;

            List<Integer> paramPositions = new ArrayList<>();
            int paramPosition = totalCells - 1;

            int n = 0;
            while (n < numberOfParams) {
                paramPositions.add(paramPosition);
                paramPosition -= 2;
                n++;
            }
            int prm = 0;
            for (int p = 0; p < totalCells; p++) {
                if (!paramPositions.contains(p)) {
                    result.append(methodNameParts[p - prm])
                            .append(" ");
                } else {
                    String parameterInsert = String.format("[%s]", method.parameters()[prm].name());
                    result.append("| ")
                            .append(parameterInsert)
                            .append(" | ");
                    prm++;
                }
            }
            if (numberOfParams == 0) {
                result.append("|");
            }
        }
        return result.toString();
    }
}
