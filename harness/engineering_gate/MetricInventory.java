import com.puppycrawl.tools.checkstyle.JavaParser;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.FullIdent;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import java.io.File;
import java.util.Set;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

/** 从固定工具的真实语法树列出计量对象，供调用方核对指标是否遗漏。 */
public final class MetricInventory {
  private static final Set<Integer> CONTAINERS =
      Set.of(
          TokenTypes.METHOD_DEF,
          TokenTypes.CTOR_DEF,
          TokenTypes.COMPACT_CTOR_DEF,
          TokenTypes.STATIC_INIT,
          TokenTypes.INSTANCE_INIT,
          TokenTypes.LAMBDA);
  private static final Set<Integer> DECISIONS =
      Set.of(
          TokenTypes.LITERAL_WHILE,
          TokenTypes.LITERAL_DO,
          TokenTypes.LITERAL_FOR,
          TokenTypes.LITERAL_IF,
          TokenTypes.LITERAL_CASE,
          TokenTypes.LITERAL_CATCH,
          TokenTypes.QUESTION,
          TokenTypes.LAND,
          TokenTypes.LOR,
          TokenTypes.LITERAL_WHEN);
  private static final Set<Integer> TYPE_BOUNDARIES =
      Set.of(
          TokenTypes.CLASS_DEF,
          TokenTypes.RECORD_DEF,
          TokenTypes.ENUM_DEF,
          TokenTypes.INTERFACE_DEF,
          TokenTypes.ANNOTATION_DEF,
          TokenTypes.OBJBLOCK);

  /** 只提供命令入口，不创建状态实例。 */
  private MetricInventory() {}

  /**
   * 输出所列文件的实际实现位置，不推断生产用途或批准计量例外。
   *
   * @param args 已由调用方核对的源码路径
   * @throws Exception 解析或输出失败时终止，不能输出成功的空清单
   */
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new IllegalArgumentException("必须显式提供源码");
    }
    XMLStreamWriter output =
        XMLOutputFactory.newFactory().createXMLStreamWriter(System.out, "UTF-8");
    output.writeStartDocument("UTF-8", "1.0");
    output.writeStartElement("inventory");
    for (String source : args) {
      output.writeStartElement("file");
      output.writeAttribute("name", source);
      DetailAST tree = JavaParser.parseFile(new File(source), JavaParser.Options.WITHOUT_COMMENTS);
      output.writeAttribute("package", packageName(tree));
      emit(tree, output);
      output.writeEndElement();
    }
    output.writeEndElement();
    output.writeEndDocument();
    output.flush();
  }

  /** 从实际包声明读取名称，不从文件路径或外层类型名称猜测包归属。 */
  private static String packageName(DetailAST tree) {
    DetailAST declaration = tree.findFirstToken(TokenTypes.PACKAGE_DEF);
    if (declaration == null) {
      return "";
    }
    DetailAST name = declaration.findFirstToken(TokenTypes.DOT);
    if (name == null) {
      name = declaration.findFirstToken(TokenTypes.IDENT);
    }
    return FullIdent.createFullIdent(name).getText();
  }

  /**
   * 遍历真实声明；抽象方法没有实现体，不制造计量覆盖。
   *
   * @param node 当前语法节点
   * @param output 结构化输出
   * @throws Exception 输出失败时终止
   */
  private static void emit(DetailAST node, XMLStreamWriter output) throws Exception {
    for (DetailAST current = node; current != null; current = current.getNextSibling()) {
      if (isImplementation(current)
          && (current.getType() == TokenTypes.LAMBDA
              || current.findFirstToken(TokenTypes.SLIST) != null)) {
        output.writeEmptyElement("implementation");
        output.writeAttribute("kind", kind(current.getType()));
        output.writeAttribute("line", Integer.toString(current.getLineNo()));
        output.writeAttribute("column", Integer.toString(current.getColumnNo() + 1));
        DetailAST body =
            current.getType() == TokenTypes.LAMBDA
                ? current.getLastChild()
                : current.findFirstToken(TokenTypes.SLIST);
        output.writeAttribute("complexity", Integer.toString(1 + decisions(body)));
      }
      emit(current.getFirstChild(), output);
    }
  }

  /**
   * 按固定工具的决定点定义计数，嵌套实现单独计量，不重复累加到外层。
   *
   * @param node 当前实现体的节点
   * @return 当前实现自身的决定点数
   */
  private static int decisions(DetailAST node) {
    int count = 0;
    for (DetailAST current = node; current != null; current = current.getNextSibling()) {
      if (!isImplementation(current) && !TYPE_BOUNDARIES.contains(current.getType())) {
        count += DECISIONS.contains(current.getType()) ? 1 : 0;
        count += decisions(current.getFirstChild());
      }
    }
    return count;
  }

  /**
   * 区分真实实现与复用 lambda 箭头 token 的 switch 分支。
   *
   * @param node 真实语法节点
   * @return 是否为独立实现范围
   */
  private static boolean isImplementation(DetailAST node) {
    return CONTAINERS.contains(node.getType())
        && (node.getType() != TokenTypes.LAMBDA
            || node.getParent().getType() != TokenTypes.SWITCH_RULE);
  }

  /**
   * 将工具节点种类映射为稳定的报告标识。
   *
   * @param token 已识别的实现节点
   * @return 对应种类
   */
  private static String kind(int token) {
    return switch (token) {
      case TokenTypes.METHOD_DEF -> "METHOD_DEF";
      case TokenTypes.CTOR_DEF -> "CTOR_DEF";
      case TokenTypes.COMPACT_CTOR_DEF -> "COMPACT_CTOR_DEF";
      case TokenTypes.STATIC_INIT -> "STATIC_INIT";
      case TokenTypes.INSTANCE_INIT -> "INSTANCE_INIT";
      case TokenTypes.LAMBDA -> "LAMBDA";
      default -> throw new IllegalArgumentException("未识别的实现节点");
    };
  }
}
