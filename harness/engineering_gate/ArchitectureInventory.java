import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/** 从固定字节码工具导出真实类型、签名和成员访问，不自行解析类文件。 */
final class ArchitectureInventory {
  /** 工具入口不提供实例状态。 */
  private ArchitectureInventory() {}

  /** 类型解析只查实际构建及 JDK；门禁自身工具版本不进入被检查工程的类路径。 */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length < 2) {
      throw new IllegalArgumentException("需要实际解析类路径和显式字节码目录");
    }
    ArchConfiguration.get().setResolveMissingDependenciesFromClassPath(true);
    ClassFileImporter importer = new ClassFileImporter();
    var urls = new ArrayList<URL>();
    for (String element : arguments[0].split(File.pathSeparator)) {
      urls.add(Path.of(element).toUri().toURL());
    }
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    try (var lookup =
        new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
      thread.setContextClassLoader(lookup);
      writeInventory(importer.importPaths(Arrays.stream(arguments).skip(1).map(Path::of).toList()));
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /** 解析及导出保持同一隔离上下文，延迟解析成员也不得借用门禁类路径。 */
  private static void writeInventory(JavaClasses classes) throws XMLStreamException {
    XMLStreamWriter output =
        XMLOutputFactory.newFactory().createXMLStreamWriter(System.out, "UTF-8");
    output.writeStartDocument("UTF-8", "1.0");
    output.writeStartElement("inventory");
    for (JavaClass type :
        classes.stream().sorted(Comparator.comparing(JavaClass::getName)).toList()) {
      writeClass(output, type);
    }
    output.writeEndElement();
    output.writeEndDocument();
    output.flush();
  }

  /** 每个实际导入类型都保留自己的成员与依赖，包含局部和匿名实现。 */
  private static void writeClass(XMLStreamWriter output, JavaClass type) throws XMLStreamException {
    output.writeStartElement("class");
    output.writeAttribute("name", type.getName());
    output.writeAttribute("package", type.getPackageName());
    output.writeAttribute(
        "source_uri", type.getSource().map(source -> source.getUri().toString()).orElse(""));
    output.writeAttribute(
        "source_name", type.getSource().flatMap(source -> source.getFileName()).orElse(""));
    output.writeAttribute(
        "public", Boolean.toString(type.getModifiers().contains(JavaModifier.PUBLIC)));
    output.writeAttribute("fully_imported", Boolean.toString(type.isFullyImported()));
    for (JavaMember member : type.getMembers()) {
      writeMember(output, member);
    }
    writeShape(output, type);
    for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
      output.writeStartElement("dependency");
      writeType(output, dependency.getTargetClass());
      output.writeAttribute("description", dependency.getDescription());
      output.writeEndElement();
    }
    for (JavaAccess<?> access : type.getAccessesFromSelf()) {
      writeAccess(output, access);
    }
    output.writeEndElement();
  }

  /** 导出数据形状的实例字段和泛型继承，不把服务的内部操作当作传输合同。 */
  private static void writeShape(XMLStreamWriter output, JavaClass type) throws XMLStreamException {
    for (var annotation : type.getAnnotations()) {
      writeAnnotationShape(output, annotation, type.getName() + ":annotation");
    }
    for (JavaMember member : type.getMembers()) {
      if (member.getModifiers().contains(JavaModifier.PUBLIC)) {
        for (var annotation : member.getAnnotations()) {
          writeAnnotationShape(output, annotation, memberId(member) + ":annotation");
        }
      }
    }
    var parents = new ArrayList<>(type.getInterfaces());
    type.getSuperclass().ifPresent(parents::add);
    for (var parent : parents) {
      for (JavaClass reference : parent.getAllInvolvedRawTypes()) {
        writeShapeType(output, reference, type.getName() + ":supertype");
      }
    }
    for (JavaField field : type.getFields()) {
      if (!field.getModifiers().contains(JavaModifier.STATIC)) {
        for (JavaClass reference : field.getAllInvolvedRawTypes()) {
          writeShapeType(output, reference, memberId(field));
        }
      }
    }
  }

  /** 制品合同也保留注解和参数的类型，避免仅源码路径能识别技术泄漏。 */
  private static void writeAnnotationShape(XMLStreamWriter output, Object value, String origin)
      throws XMLStreamException {
    if (value instanceof JavaAnnotation<?> annotation) {
      writeShapeType(output, annotation.getRawType(), origin);
      for (Object property : annotation.getProperties().values()) {
        writeAnnotationShape(output, property, origin);
      }
    } else if (value instanceof JavaClass type) {
      writeShapeType(output, type, origin);
    } else if (value instanceof JavaEnumConstant constant) {
      writeShapeType(output, constant.getDeclaringClass(), origin);
    } else if (value instanceof Object[] values) {
      for (Object element : values) {
        writeAnnotationShape(output, element, origin);
      }
    }
  }

  /** 形状引用保留真实成员和类型来源，便于报告从合同到泄漏点的路径。 */
  private static void writeShapeType(XMLStreamWriter output, JavaClass reference, String origin)
      throws XMLStreamException {
    output.writeStartElement("shape_type");
    output.writeAttribute("origin", origin);
    writeType(output, reference);
    output.writeEndElement();
  }

  /** 成员标识包含参数及结果类型，不能将同名或不同返回合同视为相同对象。 */
  private static String memberId(JavaMember member) {
    String prefix = member.getOwner().getName() + "." + member.getName();
    if (member instanceof JavaField field) {
      return prefix + ":" + field.getRawType().getName();
    }
    JavaCodeUnit code = (JavaCodeUnit) member;
    String parameters =
        code.getRawParameterTypes().stream()
            .map(JavaClass::getName)
            .collect(Collectors.joining(","));
    return prefix + "(" + parameters + "):" + code.getRawReturnType().getName();
  }

  /** 采集成员签名涉及的泛型、参数、结果及声明异常类型，不只观察擦除后的名称。 */
  private static void writeMember(XMLStreamWriter output, JavaMember member)
      throws XMLStreamException {
    output.writeStartElement("member");
    output.writeAttribute("id", memberId(member));
    output.writeAttribute(
        "public", Boolean.toString(member.getModifiers().contains(JavaModifier.PUBLIC)));
    for (JavaClass reference : member.getAllInvolvedRawTypes()) {
      output.writeStartElement("signature_type");
      writeType(output, reference);
      output.writeEndElement();
    }
    output.writeEndElement();
  }

  /** 访问目标名称与实际解析到的声明成员分别保留，继承访问不能只按文本猜测。 */
  private static void writeAccess(XMLStreamWriter output, JavaAccess<?> access)
      throws XMLStreamException {
    output.writeStartElement("access");
    output.writeAttribute("origin", memberId(access.getOrigin()));
    output.writeAttribute("target_owner", access.getTargetOwner().getName());
    output.writeAttribute("target", access.getTarget().getFullName());
    var member = access.getTarget().resolveMember();
    output.writeAttribute("resolved", member.map(ArchitectureInventory::memberId).orElse(""));
    output.writeAttribute(
        "resolved_owner", member.map(value -> value.getOwner().getName()).orElse(""));
    if (member.isPresent()) {
      writeOrigin(output, member.get().getOwner(), "resolved_");
    }
    output.writeEndElement();
  }

  /** 数组核对基础元素类型，原始类型无需类文件；缺失非原始类型保持不可用。 */
  private static void writeType(XMLStreamWriter output, JavaClass type) throws XMLStreamException {
    JavaClass base = type.getBaseComponentType();
    output.writeAttribute("target", base.getName());
    writeOrigin(output, base, "");
  }

  /** 记录类型及继承成员声明者的原生来源，不将成功解析等同于输入已覆盖。 */
  private static void writeOrigin(XMLStreamWriter output, JavaClass type, String prefix)
      throws XMLStreamException {
    output.writeAttribute(prefix + "primitive", Boolean.toString(type.isPrimitive()));
    output.writeAttribute(
        prefix + "fully_imported", Boolean.toString(type.isPrimitive() || type.isFullyImported()));
    output.writeAttribute(
        prefix + "source_uri",
        type.getSource().map(source -> source.getUri().toString()).orElse(""));
    output.writeAttribute(
        prefix + "origin_uri",
        type.getSource().map(source -> originUri(source.getUri())).orElse(""));
  }

  /** 交由 JDK 识别归档来源，不自行拆分 JAR URL 或读取清单补造构建依赖。 */
  private static String originUri(URI source) {
    if (!"jar".equals(source.getScheme())) {
      return source.toString();
    }
    try {
      var connection = (JarURLConnection) source.toURL().openConnection();
      return connection.getJarFileURL().toURI().toString();
    } catch (IOException | URISyntaxException error) {
      throw new IllegalArgumentException("无法核对实际归档来源", error);
    }
  }
}
