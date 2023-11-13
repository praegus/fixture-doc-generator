package nl.praegus.doclets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.util.DocTrees;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;
import nl.praegus.doclets.util.Tag;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class FixtureDocGenerator implements Doclet {

    private static final Set<String> METHODS_TO_IGNORE;
    public static final String USAGE = "usage";
    public static final String CONTEXTHELP = "contexthelp";
    public static final String NAME = "name";
    public static final String READABLE_NAME = "readableName";
    public static final String DOC_STRING = "docString";
    public static final String EXCEPTIONS = "exceptions";
    public static final String ANNOTATIONS = "annotations";
    public static final String PARAMETERS = "parameters";
    public static final String TYPE_NAME = "typeName";
    public static final String QUALIFIED_NAME = "qualifiedName";

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
    private static final Pattern PATTERN_USAGE = Pattern.compile(".*[U|u]sage:\\s(\\|[\\w\\s|()\\[\\]\\\\]+\\|).*", Pattern.DOTALL);
    private static final Map<String, Object> staticFields = new HashMap<>();
    private DocTrees treeUtils;

    @Override
    public void init(Locale locale, Reporter reporter) {
        //not needed for fixture docs
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public Set<? extends Option> getSupportedOptions() {
        return Collections.emptySet();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return null;
    }

    @Override
    public boolean run(DocletEnvironment environment) {
        treeUtils = environment.getDocTrees();
        Set<TypeElement> classes = ElementFilter.typesIn(environment.getIncludedElements());
        populateStaticFieldLibrary(classes);
        for (TypeElement c : classes) {
            parseClass(c);
        }
        return true;
    }

    private static void populateStaticFieldLibrary(Set<TypeElement> classes) {
        for (TypeElement c : classes) {
            for (VariableElement f : ElementFilter.fieldsIn(c.getEnclosedElements())) {
                if (f.getModifiers().contains(Modifier.PUBLIC) && f.getModifiers().contains(Modifier.STATIC)) {
                    staticFields.put(c.getQualifiedName().toString() + "#" + f.getSimpleName().toString(), f.getConstantValue());
                }
            }
        }
    }

    private void parseClass(TypeElement c) {
        Map<String, Object> props = new HashMap<>();
        props.put(TYPE_NAME, c.getQualifiedName().toString());
        props.put(NAME, c.getSimpleName().toString());
        props.put(QUALIFIED_NAME, c.getQualifiedName().toString());

        getAnnotations(c, props);

        ArrayList<Object> constructors = new ArrayList<>();
        for (ExecutableElement constructor : ElementFilter.constructorsIn(c.getEnclosedElements())) {
            if (constructor.getModifiers().contains(Modifier.PUBLIC)) {
                constructors.add(parseConstructor(c, constructor));
            }
        }
        props.put("constructors", constructors);

        ArrayList<Object> methods = new ArrayList<>();
        collectAllPublicMethods(c, methods);
        props.put("publicMethods", methods);

        writeJson(new File("." + File.separator + c.getQualifiedName().toString() + ".json"), props);

        for (TypeElement ic : ElementFilter.typesIn(c.getEnclosedElements())) {
            parseClass(ic);
        }
    }

    private static void getAnnotations(Element c, Map<String, Object> props) {
        ArrayList<Object> annotations = new ArrayList<>();
        for (AnnotationMirror a : c.getAnnotationMirrors()) {
            annotations.add(simpleNameForFQN(a.getAnnotationType().toString()));
        }
        props.put(ANNOTATIONS, annotations);
    }

    private void collectAllPublicMethods(TypeElement c, ArrayList<Object> methods) {
        for (ExecutableElement method : ElementFilter.methodsIn(c.getEnclosedElements())) {
            if (method.getModifiers().contains(Modifier.PUBLIC) && !METHODS_TO_IGNORE.contains(method.getSimpleName().toString())) {
                methods.add(parseMethod(method));
            }
        }

        if (c.getSuperclass().getKind() != TypeKind.NONE) {
            TypeMirror superClassTypeMirror = c.getSuperclass();
            TypeElement superClassTypeElement = (TypeElement) ((DeclaredType) superClassTypeMirror).asElement();
            collectAllPublicMethods(superClassTypeElement, methods);
        }
    }

    private Object parseMethod(ExecutableElement method) {

        HashMap<String, Object> properties = new HashMap<>();
        String docString = getJavaDocAsString(method);


        if (getBlockTags(method).stream().anyMatch(tag -> tag.getName().equalsIgnoreCase("@deprecated"))) {
            String lineBreak = !docString.isEmpty() ? "\r\n" : "";
            docString += lineBreak + "<b>Deprecated:</b> " + getBlockTags(method).stream().
                    filter(tag -> tag.getName().equalsIgnoreCase("@deprecated")).
                    findFirst().get().getText();
        }
        properties.put(NAME, method.getSimpleName().toString());
        properties.put(READABLE_NAME, splitCamelCase(method.getSimpleName().toString()));
        properties.put(DOC_STRING, docString);

        getElementInfo(method, properties);

        properties.put("returnType", simpleNameForFQN(method.getReturnType().toString()));

        if (getBlockTags(method).stream().anyMatch(tag -> tag.getName().equalsIgnoreCase("@return"))) {
            properties.put("returnDescription",
                    getBlockTags(method).stream().
                            filter(tag -> tag.getName().equalsIgnoreCase("@return")).
                            findAny().get().getText());
        }

        Matcher matcher = PATTERN_USAGE.matcher(getJavaDocAsString(method));
        String usage = matcher.matches() ? matcher.group(1) : generateMethodUsageString(method);

        properties.put(USAGE, usage);
        String contextHelp = usage.substring(2)
                .replaceAll("\\| \\[(\\w+)] \\|", "&lt;$1&gt;")
                .replace("|", "")
                .trim();
        properties.put(CONTEXTHELP, contextHelp);

        return properties;
    }

    private Object parseConstructor(TypeElement c, ExecutableElement constructor) {
        HashMap<String, Object> properties = new HashMap<>();

        getElementInfo(constructor, properties);

        properties.put(NAME, c.getSimpleName().toString());
        properties.put(READABLE_NAME, splitCamelCase(c.getSimpleName().toString()));
        properties.put(DOC_STRING, getJavaDocAsString(constructor));

        Matcher matcher = PATTERN_USAGE.matcher(getJavaDocAsString(constructor));
        if (matcher.matches()) {
            properties.put(USAGE, matcher.group(1));
        } else {
            properties.put(USAGE, generateConstructorUsageString(c, constructor));
        }

        return properties;
    }

    private void getElementInfo(ExecutableElement method, HashMap<String, Object> properties) {
        ArrayList<Object> exceptions = new ArrayList<>();
        for (TypeMirror exception : method.getThrownTypes()) {
            exceptions.add(exception.toString());
        }
        properties.put(EXCEPTIONS, exceptions);

        getAnnotations(method, properties);

        ArrayList<Object> parameters = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            parameters.add(parseParameter(parameter));
        }

        properties.put(PARAMETERS, parameters);
    }

    private String replaceValueRef(String description) {
        Pattern paramPattern = Pattern.compile(".*\\{(@value.+)}.*");
        Matcher m = paramPattern.matcher(description);
        if (m.matches()) {
            String replace = m.group(1);
            if (staticFields.containsKey(replace.substring(7))) {
                return description.replace("{" + replace + "}", staticFields.get(replace.substring(7)).toString());
            }
        }
        return description;
    }

    private static String simpleNameForFQN(String fqn) {
        boolean isParameterized = fqn.matches(".*<.+>");
        String result;
        if (isParameterized) {
            String[] parts = fqn.split("<");
            String fqClassName = parts[0];
            String paramClassName = parts[1].substring(0, parts[1].length() - 1);

            result = String.format("%s&lt;%s&gt;", simpleNameForFQN(fqClassName), simpleNameForFQN(paramClassName));
        }
        else {
            String[] parts = fqn.split("[.]");
            result = parts[parts.length-1];
        }
        return result;
    }

    private String getJavaDocAsString(Element d) {
        DocCommentTree dcTree = treeUtils.getDocCommentTree(d);
        return dcTree != null ? dcTree.toString() : "";
    }

    private Set<Tag> getBlockTags(Element d) {
        Set<Tag> tags = new HashSet<>();
        DocCommentTree dcTree = treeUtils.getDocCommentTree(d);
        if(null != dcTree) {
            tags = dcTree.getBlockTags().stream().filter(ParamTree.class::isInstance)
                    .map(t -> new Tag(((ParamTree) t).getName().toString(), ((ParamTree) t).getDescription().toString())).collect(Collectors.toSet());
        }
        return tags;
    }

    private HashMap<String, String> parseParameter(VariableElement parameter) {
        Set<Tag> paramTags = getBlockTags(parameter.getEnclosingElement());
        HashMap<String, String> properties = new HashMap<>();
        properties.put(NAME, parameter.getSimpleName().toString());
        properties.put("type", simpleNameForFQN(parameter.asType().toString()));
        for (Tag tag : paramTags) {
            if (tag.getName().equals(parameter.getSimpleName().toString())) {
                properties.put("description", replaceValueRef(tag.getText()));
                break;
            }
        }
        return properties;
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

    private String generateConstructorUsageString(TypeElement c, ExecutableElement constructor) {
        StringBuilder wikiText = new StringBuilder("| ");
        List<? extends VariableElement> parameters = constructor.getParameters();
        wikiText.append(splitCamelCase(c.getSimpleName().toString()))
                .append(" |");
        for (VariableElement parameter : parameters) {
            String paramDisplay = String.format(" [%s]", parameter.getSimpleName());
            wikiText.append(paramDisplay)
                    .append(" |");
        }
        return wikiText.toString();
    }

    private static String generateMethodUsageString(ExecutableElement method) {
        String readableMethodName = splitCamelCase(method.getSimpleName().toString());
        String[] methodNameParts = readableMethodName.split(" ");

        int numberOfParts = methodNameParts.length;
        int numberOfParams = method.getParameters().size();

        StringBuilder result = new StringBuilder("| ");

        if (numberOfParams > numberOfParts) {
            result.append(readableMethodName)
                    .append(" | ");
            for (VariableElement parameter : method.getParameters()) {
                result.append(parameter.getSimpleName().toString())
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
                    String parameterInsert = String.format("[%s]", method.getParameters().get(prm).getSimpleName().toString());
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

    private static void writeJson(File f, Object o) {
        try {
            Files.deleteIfExists(f.toPath());
            ObjectMapper m = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            m.writeValue(f, o);
        } catch (Exception e) {
            System.err.printf("Error writing %s:%n%s%n", f.getPath(), e.getMessage());
        }

    }
}
