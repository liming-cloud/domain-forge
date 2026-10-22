"""通过真实 JDK 和 MyBatis 类型校准 SQL 来源；不以关键词扫描代替语义解析。"""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


class SqlSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="domain-forge-sql-source-")
        cls.addClassCleanup(cls.directory.cleanup)
        cls.root = Path(cls.directory.name)
        repository = Path(os.environ["DOMAIN_FORGE_PRODUCT_REPOSITORY"])
        cls.dependencies = [
            repository / "org/mybatis/mybatis/3.5.19/mybatis-3.5.19.jar",
            repository / "com/baomidou/mybatis-plus-core/3.5.17/mybatis-plus-core-3.5.17.jar",
            repository / "com/baomidou/mybatis-plus-annotation/3.5.17/mybatis-plus-annotation-3.5.17.jar",
        ]
        if not all(path.is_file() for path in cls.dependencies):
            raise AssertionError("缺少固定真实 MyBatis 依赖，不能跳过")
        subprocess.run(["javac", "--release", "21", "-d", str(cls.root),
                        str(Path(__file__).with_name("MethodInventory.java"))],
                       check=True, capture_output=True, text=True, timeout=30)

    def inspect(self, body):
        path = self.root / "Sample.java"
        path.write_text("package probe;\n" + body)
        process = subprocess.run(["java", "-cp", str(self.root), "MethodInventory",
                                  os.pathsep.join(map(str, self.dependencies)), str(path)],
                                 check=True, capture_output=True, text=True, timeout=30)
        output = ET.fromstring(process.stdout)
        self.assertEqual(output.find("file").get("name"), str(path))
        return [node.attrib for node in output.findall(".//sql_violation")]

    def test_sql_annotations_and_providers_are_rejected_by_resolved_type(self):
        for annotation in ('@org.apache.ibatis.annotations.Select("select 1")',
                           '@org.apache.ibatis.annotations.Update("update t set n=1")',
                           '@org.apache.ibatis.annotations.SelectProvider(type=Sample.class, method="sql")'):
            with self.subTest(annotation=annotation):
                issues = self.inspect("interface Sample {\n" + annotation + "\n int read();\n}")
                self.assertIn("SG_SQL_ANNOTATION", {item["code"] for item in issues})

    def test_java_sql_and_raw_wrapper_fragments_are_rejected(self):
        for body in (
            'class Sample { String sql = "select id from accounts"; }',
            'class Sample { String sql = """\n SELECT id FROM accounts\n """; }',
            'class Sample { void run(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> q) { q.last("for update"); } }',
            'class Sample { void run(java.sql.Connection c, String input) throws java.sql.SQLException { c.prepareStatement(input); } }',
        ):
            with self.subTest(body=body):
                self.assertTrue(self.inspect(body), "可执行 SQL 来源必须有目标诊断")

    def test_xml_mapper_and_typed_chain_are_allowed(self):
        self.assertEqual([], self.inspect("""
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper interface Sample { String read(@Param("id") long id); }
class Row { public Long getId() { return 1L; } }
class Query {
  Object find() {
    return new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Row>()
        .eq(Row::getId, 1L);
  }
  String message() { return "select a supported option"; }
}
"""))


if __name__ == "__main__":
    unittest.main()
