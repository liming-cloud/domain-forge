import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.ErroneousTree;
import com.sun.source.doctree.InheritDocTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTreeScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/** 使用公开 JDK 编译器 API 关联声明、擦除后的二进制签名和文档位置。 */
final class MethodInventory extends TreePathScanner<Void, Void> {
  private static final Map<TypeKind, String> PRIMITIVE_DESCRIPTORS =
      Map.of(
          TypeKind.BOOLEAN,
          "Z",
          TypeKind.BYTE,
          "B",
          TypeKind.CHAR,
          "C",
          TypeKind.SHORT,
          "S",
          TypeKind.INT,
          "I",
          TypeKind.LONG,
          "J",
          TypeKind.FLOAT,
          "F",
          TypeKind.DOUBLE,
          "D");
  private final DocTrees trees;
  private final Elements elements;
  private final Types types;
  private final XMLStreamWriter output;
  private final StandardJavaFileManager files;
  private final Set<String> referenced = new HashSet<>();

  /** 本次分析使用同一个编译任务，避免按文本拼接类型或寻找同名方法。 */
  private MethodInventory(JavacTask task, StandardJavaFileManager files, XMLStreamWriter output) {
    trees = DocTrees.instance(task);
    elements = task.getElements();
    types = task.getTypes();
    this.output = output;
    this.files = files;
  }

  /** 只分析实际维护源码及类产物，不运行注解处理器、不生成或覆盖类文件。 */
  public static void main(String[] arguments) throws IOException, XMLStreamException {
    if (arguments.length < 2) {
      throw new IllegalArgumentException("需要实际类产物路径与源码");
    }
    var compiler = ToolProvider.getSystemJavaCompiler();
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
      files.setLocationFromPaths(
          StandardLocation.CLASS_PATH,
          Arrays.stream(arguments[0].split(java.io.File.pathSeparator)).map(Path::of).toList());
      files.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
      var sources =
          files.getJavaFileObjectsFromStrings(
              Arrays.asList(arguments).subList(1, arguments.length));
      var options =
          List.of("--release", "21", "-proc:none", "-implicit:none", "-encoding", "UTF-8");
      var task = (JavacTask) compiler.getTask(null, files, diagnostics, options, null, sources);
      var units = new ArrayList<CompilationUnitTree>();
      task.parse().forEach(units::add);
      task.analyze();
      if (diagnostics.getDiagnostics().stream()
          .anyMatch(item -> item.getKind() == Diagnostic.Kind.ERROR)) {
        throw new IllegalArgumentException("实际源码语义分析失败：" + diagnostics.getDiagnostics());
      }
      writeInventory(task, files, units);
    }
  }

  /** 逐个文件保留实际解析范围，零方法文件也不能从输入核对中消失。 */
  private static void writeInventory(
      JavacTask task, StandardJavaFileManager files, List<CompilationUnitTree> units)
      throws XMLStreamException {
    var output = XMLOutputFactory.newFactory().createXMLStreamWriter(System.out, "UTF-8");
    output.writeStartElement("inventory");
    var visitor = new MethodInventory(task, files, output);
    for (var unit : units) {
      output.writeStartElement("file");
      output.writeAttribute("name", Path.of(unit.getSourceFile().toUri()).toString());
      visitor.referenced.clear();
      visitor.scan(unit, null);
      output.writeEndElement();
    }
    output.writeEndElement();
    output.flush();
  }

  /** 只记录源码真实引用的类型，不把 SDK 方法内部声明者当成用户依赖。 */
  @Override
  public Void visitIdentifier(IdentifierTree tree, Void unused) {
    writeReference();
    return super.visitIdentifier(tree, unused);
  }

  /** 限定名、导入、注解及内联常量的类型由同一次语义分析解析。 */
  @Override
  public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
    writeReference();
    return super.visitMemberSelect(tree, unused);
  }

  /** 源码、JDK 模块和真实类路径对象分别记录，不能按包名前缀猜测来源。 */
  private void writeReference() {
    var unit = getCurrentPath().getCompilationUnit();
    long start = trees.getSourcePositions().getStartPosition(unit, getCurrentPath().getLeaf());
    var type = referenceType();
    if (type == null || start < 0) {
      return;
    }
    String name = elements.getBinaryName(type).toString();
    String usage = referenceUsage();
    String owner = referenceOwner();
    if (!referenced.add(owner + ":" + name + ":" + usage)) {
      return;
    }
    try {
      output.writeEmptyElement("type_reference");
      output.writeAttribute("target", name);
      output.writeAttribute("owner", owner);
      output.writeAttribute("usage", usage);
      output.writeAttribute("line", Long.toString(unit.getLineMap().getLineNumber(start)));
      if (trees.getPath(type) != null) {
        output.writeAttribute("kind", "source");
      } else if (!elements.getModuleOf(type).isUnnamed()) {
        output.writeAttribute("kind", "platform");
        output.writeAttribute("module", elements.getModuleOf(type).getQualifiedName().toString());
      } else {
        output.writeAttribute("kind", "classpath");
        var source =
            files.getJavaFileForInput(StandardLocation.CLASS_PATH, name, JavaFileObject.Kind.CLASS);
        if (source == null) {
          throw new IllegalArgumentException("源码引用类型缺少实际类文件：" + name);
        }
        output.writeAttribute("origin_uri", archiveOrigin(source.toUri()).toString());
      }
    } catch (IOException | XMLStreamException failure) {
      throw new IllegalStateException(failure);
    }
  }

  /** 编译期常量也保留实际消费者，但继承实现不能替代源码选择的公开入口。 */
  private TypeElement referenceType() {
    var element = trees.getElement(getCurrentPath());
    if (element instanceof TypeElement type) {
      return type;
    }
    if (!(getCurrentPath().getLeaf() instanceof IdentifierTree)
        || !(element instanceof VariableElement constant)
        || constant.getConstantValue() == null) {
      return null;
    }
    var unit = getCurrentPath().getCompilationUnit();
    for (var declaration : unit.getImports()) {
      var type = staticConstantType(unit, declaration, constant);
      if (type != null) {
        return type;
      }
    }
    return null;
  }

  /** 静态导入按原生符号与继承成员核对，具名和通配导入使用同一关联规则。 */
  private TypeElement staticConstantType(
      CompilationUnitTree unit, ImportTree declaration, VariableElement constant) {
    if (!declaration.isStatic()
        || !(declaration.getQualifiedIdentifier() instanceof MemberSelectTree selector)) {
      return null;
    }
    var name = selector.getIdentifier();
    if (!name.contentEquals(constant.getSimpleName()) && !name.contentEquals("*")) {
      return null;
    }
    var element = trees.getElement(TreePath.getPath(unit, selector.getExpression()));
    if (element instanceof TypeElement type && elements.getAllMembers(type).contains(constant)) {
      return type;
    }
    return null;
  }

  /** 保留注解、参数、导入和普通类型使用，不能因同名注解先出现而掩盖技术操作。 */
  private String referenceUsage() {
    for (var path = getCurrentPath(); path != null; path = path.getParentPath()) {
      if (path.getLeaf() instanceof ImportTree) {
        return "import";
      }
      if (path.getLeaf() instanceof AnnotationTree annotation) {
        return annotation.getAnnotationType() == getCurrentPath().getLeaf()
            ? "annotation"
            : "annotation-value";
      }
    }
    return "type";
  }

  /** 归属由同次语义树确定，包含局部和匿名类型，不用源码路径或可见性推定。 */
  private String referenceOwner() {
    for (var path = getCurrentPath(); path != null; path = path.getParentPath()) {
      if (path.getLeaf() instanceof ClassTree
          && trees.getElement(path) instanceof TypeElement type) {
        return elements.getBinaryName(type).toString();
      }
    }
    return "";
  }

  /** JDK 原生连接解出实际 JAR 来源，不维护自制 JAR URL 或文件格式解析器。 */
  private static URI archiveOrigin(URI source) throws IOException {
    if (!source.getScheme().equals("jar")) {
      return source;
    }
    try {
      var connection = (JarURLConnection) source.toURL().openConnection();
      return connection.getJarFileURL().toURI();
    } catch (URISyntaxException failure) {
      throw new IllegalArgumentException("源码引用的 JAR 来源无效", failure);
    }
  }

  /** 抽象方法、构造器及局部方法使用同一声明映射，隐式成员不伪造源码位置。 */
  @Override
  public Void visitMethod(MethodTree tree, Void unused) {
    try {
      var method = (ExecutableElement) trees.getElement(getCurrentPath());
      var unit = getCurrentPath().getCompilationUnit();
      long start = trees.getSourcePositions().getStartPosition(unit, tree);
      long end = trees.getSourcePositions().getEndPosition(unit, tree);
      output.writeStartElement("method");
      output.writeAttribute("id", memberId(method));
      output.writeAttribute(
          "owner", elements.getBinaryName((TypeElement) method.getEnclosingElement()).toString());
      output.writeAttribute(
          "explicit",
          Boolean.toString(
              elements.getOrigin(method) == Elements.Origin.EXPLICIT && start >= 0 && end >= 0));
      output.writeAttribute("start", Long.toString(start));
      output.writeAttribute("end", Long.toString(end));
      output.writeAttribute(
          "line", Long.toString(start < 0 ? -1 : unit.getLineMap().getLineNumber(start)));
      writeComment(unit, trees.getDocCommentTree(getCurrentPath()));
      writeOverrides(method);
      output.writeEndElement();
    } catch (XMLStreamException failure) {
      throw new IllegalStateException(failure);
    }
    return super.visitMethod(tree, unused);
  }

  /** 文档 AST 提供真实位置与继承标记；有文字不代表中文、内容或行为已正确。 */
  private void writeComment(CompilationUnitTree unit, DocCommentTree comment)
      throws XMLStreamException {
    output.writeAttribute("doc_present", Boolean.toString(comment != null));
    if (comment == null) {
      return;
    }
    var facts = new CommentFacts();
    facts.scan(comment, null);
    output.writeAttribute(
        "doc_start",
        Long.toString(trees.getSourcePositions().getStartPosition(unit, comment, comment)));
    output.writeAttribute(
        "doc_end",
        Long.toString(trees.getSourcePositions().getEndPosition(unit, comment, comment)));
    output.writeAttribute("doc_body", Boolean.toString(!comment.getFullBody().isEmpty()));
    output.writeAttribute("doc_inherit", Boolean.toString(facts.inherited));
    output.writeAttribute("doc_error", Boolean.toString(facts.erroneous));
  }

  /** 覆盖关系由实际类型系统判断，普通同名方法或 Override 注解不能产生继承合同。 */
  private void writeOverrides(ExecutableElement method) throws XMLStreamException {
    var owner = (TypeElement) method.getEnclosingElement();
    var pending = new ArrayDeque<TypeMirror>(types.directSupertypes(owner.asType()));
    Set<TypeElement> visited = new HashSet<>();
    while (!pending.isEmpty()) {
      var parent = (TypeElement) types.asElement(pending.remove());
      if (!visited.add(parent)) {
        continue;
      }
      for (var candidate : ElementFilter.methodsIn(parent.getEnclosedElements())) {
        if (elements.overrides(method, candidate, owner)) {
          output.writeEmptyElement("overrides");
          output.writeAttribute("id", memberId(candidate));
        }
      }
      pending.addAll(types.directSupertypes(parent.asType()));
    }
  }

  /** 二进制标识包含擦除后的参数及结果，与实际字节码清单逐项相等才算绑定。 */
  private String memberId(ExecutableElement method) {
    var owner = (TypeElement) method.getEnclosingElement();
    var parameters = new ArrayList<String>();
    if (method.getKind() == ElementKind.CONSTRUCTOR
        && owner.getNestingKind() == NestingKind.MEMBER
        && !owner.getModifiers().contains(Modifier.STATIC)) {
      parameters.add(typeName(owner.getEnclosingElement().asType()));
    }
    method.getParameters().forEach(parameter -> parameters.add(typeName(parameter.asType())));
    return elements.getBinaryName(owner)
        + "."
        + method.getSimpleName()
        + "("
        + parameters.stream().collect(Collectors.joining(","))
        + "):"
        + typeName(method.getReturnType());
  }

  /** 数组保留 JVM 名称，嵌套类型保留二进制名，泛型按编译器擦除。 */
  private String typeName(TypeMirror mirror) {
    var erased = types.erasure(mirror);
    return switch (erased.getKind()) {
      case ARRAY -> descriptor(erased);
      case DECLARED -> elements.getBinaryName((TypeElement) types.asElement(erased)).toString();
      default -> erased.getKind().name().toLowerCase(Locale.ROOT);
    };
  }

  /** 数组成员按实际二进制描述符输出，不把可变参数当成另一种参数类型。 */
  private String descriptor(TypeMirror mirror) {
    if (mirror.getKind() == TypeKind.ARRAY) {
      return "[" + descriptor(((ArrayType) mirror).getComponentType());
    }
    if (mirror.getKind().isPrimitive()) {
      return PRIMITIVE_DESCRIPTORS.get(mirror.getKind());
    }
    return "L" + typeName(mirror) + ";";
  }

  /** 仅收集机械事实，不将继承标记或非空正文当作语义批准。 */
  private static final class CommentFacts extends DocTreeScanner<Void, Void> {
    private boolean inherited;
    private boolean erroneous;

    /** 保留真实继承标记，后续必须能定位有效源合同。 */
    @Override
    public Void visitInheritDoc(InheritDocTree tree, Void unused) {
      inherited = true;
      return super.visitInheritDoc(tree, unused);
    }

    /** 解析器识别的坏文档不能以非空正文冒充有效说明。 */
    @Override
    public Void visitErroneous(ErroneousTree tree, Void unused) {
      erroneous = true;
      return super.visitErroneous(tree, unused);
    }
  }
}
