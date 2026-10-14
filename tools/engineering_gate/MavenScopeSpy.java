package org.domainforge.gate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;

/** 观察 Maven 实际项目、解析对象及成功执行，只导出验证事实，不导出配置属性或凭证。 */
public final class MavenScopeSpy extends AbstractEventSpy {
  private final List<Compilation> compilations = new ArrayList<>();
  private final List<Goal> goals = new ArrayList<>();
  private final List<DependencyResolutionResult> resolutions = new ArrayList<>();
  private final List<DependencyNode> pluginNodes = Collections.synchronizedList(new ArrayList<>());

  /** 保存成功目标及其实际插件类路径；成功事件本身不证明测试未跳过。 */
  private record Goal(
      String project, MojoExecution mojo, List<Artifact> artifacts, List<Artifact> dependencies) {}

  /** 保存执行时的源码根、输出及原生类路径，不根据目录名称猜测用途。 */
  private record Compilation(
      String project,
      String goal,
      String execution,
      String version,
      List<String> roots,
      String output,
      List<String> classpath,
      List<Artifact> dependencies) {}

  /** 观察固定 Maven 生命周期及原生解析结果；失败仍由原生退出状态表达。 */
  @Override
  public void onEvent(Object value) throws Exception {
    if (value instanceof RepositoryEvent event) {
      observeRepository(event);
      return;
    }
    if (value instanceof DependencyResolutionResult result) {
      resolutions.add(result);
      return;
    }
    if (!(value instanceof ExecutionEvent event)) {
      return;
    }
    if (event.getType() == ExecutionEvent.Type.MojoSucceeded) {
      MojoExecution mojo = event.getMojoExecution();
      goals.add(
          new Goal(
              event.getProject().getBasedir().getAbsolutePath(),
              mojo,
              List.copyOf(mojo.getMojoDescriptor().getPluginDescriptor().getArtifacts()),
              List.copyOf(event.getProject().getArtifacts())));
      observeCompiler(event);
    }
    if (event.getType() == ExecutionEvent.Type.SessionEnded) {
      writeInventory(event);
    }
  }

  /** 只保留原生成功解析的插件节点；请求链不导出属性、远端地址或认证数据。 */
  private void observeRepository(RepositoryEvent event) {
    if (event.getType() != RepositoryEvent.EventType.ARTIFACT_RESOLVED
        || event.getException() != null) {
      return;
    }
    for (RequestTrace trace = event.getTrace(); trace != null; trace = trace.getParent()) {
      if (trace.getData() instanceof ArtifactRequest request
          && request.getDependencyNode() != null
          && "plugin".equals(request.getRequestContext())) {
        pluginNodes.add(request.getDependencyNode());
        return;
      }
    }
  }

  /** 编译器成功事件确定用途；无事件或无实际输入时，调用方不能自行补造。 */
  private void observeCompiler(ExecutionEvent event) throws DependencyResolutionRequiredException {
    MojoExecution mojo = event.getMojoExecution();
    if (!mojo.getGroupId().equals("org.apache.maven.plugins")
        || !mojo.getArtifactId().equals("maven-compiler-plugin")) {
      return;
    }
    MavenProject project = event.getProject();
    boolean test = mojo.getGoal().equals("testCompile");
    if (!test && !mojo.getGoal().equals("compile")) {
      return;
    }
    compilations.add(
        new Compilation(
            project.getBasedir().getAbsolutePath(),
            mojo.getGoal(),
            mojo.getExecutionId(),
            mojo.getVersion(),
            List.copyOf(
                test ? project.getTestCompileSourceRoots() : project.getCompileSourceRoots()),
            test
                ? project.getBuild().getTestOutputDirectory()
                : project.getBuild().getOutputDirectory(),
            List.copyOf(
                test ? project.getTestClasspathElements() : project.getCompileClasspathElements()),
            List.copyOf(project.getArtifacts())));
  }

  /** 会话结束后导出最终项目和观察结果；清单不作为可信签名或人工验收。 */
  private void writeInventory(ExecutionEvent event) throws Exception {
    Path destination = Path.of(System.getProperty("domainforge.scope.output"));
    try (var stream = Files.newOutputStream(destination)) {
      XMLStreamWriter output = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
      output.writeStartElement("inventory");
      output.writeAttribute("mavenHome", System.getProperty("maven.home"));
      output.writeAttribute("javaVersion", System.getProperty("java.version"));
      output.writeAttribute(
          "repository",
          event
              .getSession()
              .getRepositorySession()
              .getLocalRepository()
              .getBasedir()
              .getAbsolutePath());
      for (String requested : event.getSession().getRequest().getGoals()) {
        output.writeEmptyElement("requested");
        output.writeAttribute("goal", requested);
      }
      for (MavenProject project : event.getSession().getProjects()) {
        writeProject(output, project);
      }
      for (Compilation compilation : compilations) {
        writeCompilation(output, compilation);
      }
      for (Goal goal : goals) {
        writeGoal(output, goal);
      }
      for (DependencyResolutionResult result : resolutions) {
        writeResolution(output, result);
      }
      for (DependencyNode node : pluginNodes) {
        writeConstraint(output, "pluginConstraint", node);
      }
      output.writeEndElement();
      output.flush();
    }
  }

  /** 项目模型保留有效声明，解析对象包含传递依赖；两者不可互相替代。 */
  private void writeProject(XMLStreamWriter output, MavenProject project)
      throws XMLStreamException {
    output.writeStartElement("project");
    output.writeAttribute("id", project.getId());
    output.writeAttribute("path", project.getBasedir().getAbsolutePath());
    output.writeAttribute("packaging", project.getPackaging());
    output.writeAttribute("build", project.getBuild().getDirectory());
    coordinates(output, project.getGroupId(), project.getArtifactId(), project.getVersion());
    output.writeAttribute(
        "artifactFile",
        project.getArtifact().getFile() == null ? "" : project.getArtifact().getFile().getPath());
    writeModel(output, project);
    var artifacts = new LinkedHashSet<>(project.getArtifacts());
    for (Goal goal : goals) {
      if (goal.project().equals(project.getBasedir().getAbsolutePath())) {
        artifacts.addAll(goal.dependencies());
      }
    }
    for (Artifact artifact : artifacts) {
      writeArtifact(output, "resolvedDependency", artifact);
    }
    output.writeEndElement();
  }

  /** 只输出版本、坐标和作用域，不导出任意 POM 属性、仓库认证或系统环境。 */
  private static void writeModel(XMLStreamWriter output, MavenProject project)
      throws XMLStreamException {
    for (var dependency : project.getDependencies()) {
      output.writeEmptyElement("declaredDependency");
      coordinates(
          output, dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
      output.writeAttribute("scope", String.valueOf(dependency.getScope()));
    }
    for (var plugin : project.getBuildPlugins()) {
      output.writeEmptyElement("declaredPlugin");
      coordinates(output, plugin.getGroupId(), plugin.getArtifactId(), plugin.getVersion());
      for (var dependency : plugin.getDependencies()) {
        output.writeEmptyElement("declaredPluginDependency");
        coordinates(
            output, dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
      }
    }
  }

  /** 目标坐标与执行身份来自成功事件，不从日志文字或调用方清单猜测。 */
  private static void writeGoal(XMLStreamWriter output, Goal goal) throws XMLStreamException {
    MojoExecution mojo = goal.mojo();
    output.writeStartElement("execution");
    output.writeAttribute("project", goal.project());
    coordinates(output, mojo.getGroupId(), mojo.getArtifactId(), mojo.getVersion());
    output.writeAttribute("goal", mojo.getGoal());
    output.writeAttribute("execution", mojo.getExecutionId());
    output.writeAttribute("phase", String.valueOf(mojo.getLifecyclePhase()));
    for (Artifact artifact : goal.artifacts()) {
      writeArtifact(output, "pluginDependency", artifact);
    }
    output.writeEndElement();
  }

  /** 保留实际版本和基础版本，时间戳快照不能被误认成固定发行。 */
  private static void writeArtifact(XMLStreamWriter output, String tag, Artifact artifact)
      throws XMLStreamException {
    output.writeEmptyElement(tag);
    coordinates(output, artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
    output.writeAttribute("baseVersion", String.valueOf(artifact.getBaseVersion()));
    output.writeAttribute("scope", String.valueOf(artifact.getScope()));
    output.writeAttribute("type", artifact.getType());
    output.writeAttribute("extension", artifact.getArtifactHandler().getExtension());
    output.writeAttribute(
        "addedToClasspath", String.valueOf(artifact.getArtifactHandler().isAddedToClasspath()));
    output.writeAttribute(
        "classifier", artifact.getClassifier() == null ? "" : artifact.getClassifier());
    output.writeAttribute("file", artifact.getFile() == null ? "" : artifact.getFile().getPath());
  }

  /** 原生图保留实际生效的版本约束，不能从已解析版本反推约束固定。 */
  private static void writeResolution(XMLStreamWriter output, DependencyResolutionResult result)
      throws XMLStreamException {
    DependencyNode root = result.getDependencyGraph();
    if (root == null) {
      return;
    }
    output.writeStartElement("dependencyResolution");
    var artifact = root.getArtifact();
    coordinates(output, artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
    var visitor = new PreorderNodeListGenerator();
    root.accept(visitor);
    for (DependencyNode node : visitor.getNodes()) {
      if (result.getResolvedDependencies().contains(node.getDependency())) {
        writeConstraint(output, "constraint", node);
        output.writeAttribute("direct", Boolean.toString(root.getChildren().contains(node)));
        output.writeAttribute("scope", node.getDependency().getScope());
      }
    }
    output.writeEndElement();
  }

  /** 只导出原生已解析节点的最小身份与约束，不自行解析上游 POM 或环境属性。 */
  private static void writeConstraint(XMLStreamWriter output, String tag, DependencyNode node)
      throws XMLStreamException {
    var artifact = node.getArtifact();
    output.writeEmptyElement(tag);
    coordinates(output, artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());
    output.writeAttribute("extension", artifact.getExtension());
    output.writeAttribute("classifier", artifact.getClassifier());
    output.writeAttribute("constraint", String.valueOf(node.getVersionConstraint()));
  }

  /** 坐标是最小公开诊断字段，缺失版本仍保留给调用方明确拒绝。 */
  private static void coordinates(
      XMLStreamWriter output, String group, String artifact, String version)
      throws XMLStreamException {
    output.writeAttribute("group", String.valueOf(group));
    output.writeAttribute("artifact", String.valueOf(artifact));
    output.writeAttribute("version", String.valueOf(version));
  }

  /** 每次执行独立保存输入根；嵌套或匿名类型由编译器实际产物清单覆盖。 */
  private static void writeCompilation(XMLStreamWriter output, Compilation compilation)
      throws XMLStreamException {
    output.writeStartElement("compilation");
    output.writeAttribute("project", compilation.project());
    output.writeAttribute("goal", compilation.goal());
    output.writeAttribute("execution", compilation.execution());
    output.writeAttribute("version", compilation.version());
    output.writeAttribute("output", compilation.output());
    for (String root : compilation.roots()) {
      output.writeEmptyElement("root");
      output.writeAttribute("path", root);
    }
    for (String path : compilation.classpath()) {
      output.writeEmptyElement("classpath");
      output.writeAttribute("path", path);
    }
    for (Artifact artifact : compilation.dependencies()) {
      writeArtifact(output, "classpathDependency", artifact);
    }
    output.writeEndElement();
  }
}
