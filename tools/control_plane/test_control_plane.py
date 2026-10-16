"""临时仓库验证公开命令；不写入项目 Git，不把夹具当作产品行为证明。"""

import hashlib
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest


ENTRY = pathlib.Path(__file__).with_name("control_plane.py")


class ControlPlaneCommands(unittest.TestCase):
    """每个场景使用自有空仓库，预期来自设计约束而非被测实现。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="domain-forge-control-plane-")
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.write("README.md", "# 产品\n\n## 目标\n\n指导独立消费及需求研发。\n")
        self.write("docs/rules.md", "# 规范\n\n## 文档\n\n只引用唯一正文。\n\n## 数据\n\n核对真实持久化。\n")
        self.write("tools/control_plane/control_plane.py", ENTRY.read_text(encoding="utf-8"))
        self.catalog = {
            "schema": 1,
            "categories": {"product": {"title": "产品", "index": "docs/product-index.md"},
                           "standards": {"title": "规范", "index": "docs/standards-index.md"},
                           "tasks": {"title": "任务", "index": "docs/tasks/index.md"}},
            "documents": {
                "PRODUCT": {"path": "README.md", "category": "product", "kind": "rules"},
                "RULES": {"path": "docs/rules.md", "category": "standards", "kind": "rules"},
                "WORK": {"path": "docs/tasks/task.md", "category": "tasks", "kind": "task"}},
            "scopes": {"documentation": "文档", "persistence": "数据", "bootstrap": "起步", "requirements": "需求", "extension": "扩展", "rework": "整改"},
            "task_types": {"documentation": ["documentation"], "create-project": ["bootstrap"],
                           "business-demand": ["requirements"], "platform-extension": ["extension"],
                           "bugfix": ["rework"]},
            "routes": [
                {"id": "goals", "scopes": ["documentation", "bootstrap", "requirements", "extension", "rework"],
                 "refs": ["PRODUCT#目标"], "checks": ["doc-integrity"]},
                {"id": "docs", "scopes": ["documentation"], "refs": ["RULES#文档"], "checks": ["doc-integrity"]},
                {"id": "data", "scopes": ["persistence"], "refs": ["RULES#数据"], "checks": ["database-scenario"]}],
            "classifiers": [{"glob": "*.md", "purpose": "documentation", "scopes": ["documentation"]},
                            {"glob": "docs/**", "purpose": "documentation", "scopes": ["documentation"]},
                            {"glob": "tools/**", "purpose": "governance", "scopes": ["documentation"]},
                            {"glob": "db/*.sql", "purpose": "production", "scopes": ["persistence"]}],
            "checks": {"doc-integrity": {"kind": "automatic", "argv": ["@python", "@entry", "check"], "implemented": True},
                       "database-scenario": {"kind": "scenario", "implemented": False}},
            "tasks": {"fixture": "WORK"}, "current_task": "fixture"}
        self.task = {"id": "fixture", "type": "documentation", "goals": ["PRODUCT#目标"],
                     "unit": "WORK#交付", "consumers": ["后续 Agent"], "scopes": ["documentation"],
                     "design_refs": ["RULES#文档"], "verification": [{"check": "doc-integrity", "criterion": "引用与派生入口一致"}],
                     "frontier": "核对路由", "pending": [], "deferred": ["产品真实接入"],
                     "acceptance": None, "closing": None, "extra_refs": []}
        self.save_catalog()
        self.save_task()
        self.git("init", "--quiet")
        self.git("-c", "user.name=Control Plane Fixture", "-c", "user.email=fixture@example.invalid", "add", ".")
        self.git("-c", "user.name=Control Plane Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture")
        self.task["base"] = self.git("rev-parse", "HEAD").strip()
        self.save_task()

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def save_catalog(self):
        self.write("tools/control_plane/catalog.json", json.dumps(self.catalog, ensure_ascii=False, indent=2) + "\n")

    def save_task(self):
        record = json.dumps(self.task, ensure_ascii=False, indent=2)
        self.write("docs/tasks/task.md", "# 当前任务\n\n## 交付\n\n路由与证据核对。\n\n```control-plane-task\n" + record + "\n```\n")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, capture_output=True, text=True, check=True).stdout

    def command(self, *args):
        result = subprocess.run([sys.executable, "-B", str(ENTRY), "--root", str(self.root), *args],
                                capture_output=True, text=True, timeout=60)
        try:
            output = json.loads(result.stdout)
        except json.JSONDecodeError:
            self.fail(f"命令未返回可核对 JSON：{result.stdout!r} {result.stderr!r}")
        return result.returncode, output

    def render_indices(self):
        code, output = self.command("index")
        self.assertEqual(code, 0, output)
        for path, content in output["files"].items():
            self.write(path, content)

    def expect_failure(self, expected, *args):
        code, output = self.command(*args)
        self.assertNotEqual(code, 0, output)
        self.assertIn(expected, [item["code"] for item in output["diagnostics"]], output)

    def review_fixture(self):
        """评审只提供当前规则与真实材料，不填写人工验收或执行任意命令。"""
        self.catalog["checks"]["semantic-review"] = {"kind": "review", "implemented": True}
        for route in self.catalog["routes"][:2]:
            route["checks"].append("semantic-review")
        self.save_catalog()
        self.render_indices()
        code, context = self.command("context", "--task", "fixture")
        self.assertEqual(code, 0, context)
        review = {"task": "fixture", "input": context["input"], "actor": "agent",
                  "coverage": [{"refs": ["PRODUCT#目标", "RULES#文档"],
                                "evidence": [{"path": path, "sha256": hashlib.sha256((self.root / path).read_bytes()).hexdigest()}
                                             for path in ("README.md", "docs/rules.md")],
                                "conclusion": "当前文档改动保持唯一规则来源及独立消费目标，未扩大产品范围。"}],
                  "findings": [], "pending_decisions": []}
        temp = tempfile.TemporaryDirectory(prefix="domain-forge-review-")
        self.addCleanup(temp.cleanup)
        path = pathlib.Path(temp.name) / "review.json"
        path.write_text(json.dumps(review, ensure_ascii=False), encoding="utf-8")
        return path, review

    def test_agent_review_accepts_covered_evidence_without_creating_human_acceptance(self):
        path, _ = self.review_fixture()
        code, report = self.command("run", "--task", "fixture", "--review", str(path))
        self.assertEqual(code, 0, report)
        self.assertFalse(report["human_acceptance"])
        reviewed = next(item for item in report["checks"] if item["id"] == "semantic-review")
        self.assertEqual(reviewed["proof_scope"], "review-record-integrity-only")
        self.assertEqual(reviewed["review"]["actor"], "agent")
        receipt = path.with_name("receipt.json")
        receipt.write_text(json.dumps(report), encoding="utf-8")
        code, result = self.command("verify-receipt", "--task", "fixture", "--receipt", str(receipt))
        self.assertEqual(code, 0, result)
        self.assertFalse(result["human_acceptance"])
        # 修改报告中的评审记录不能借外层 ok/退出码绕过关联核对。
        reviewed["review"]["coverage"] = []
        receipt.write_text(json.dumps(report), encoding="utf-8")
        self.expect_failure("CP_REVIEW_COVERAGE", "verify-receipt", "--task", "fixture", "--receipt", str(receipt))

    def test_review_missing_stale_or_unresolved_is_not_a_pass(self):
        path, review = self.review_fixture()
        self.expect_failure("CP_REVIEW_REQUIRED", "run", "--task", "fixture")
        for change, diagnostic in (({"input": "0" * 64}, "CP_STALE_REVIEW"),
                                   ({"task": "other"}, "CP_STALE_REVIEW"),
                                   ({"findings": ["存在未整改问题"]}, "CP_REVIEW_REWORK"),
                                   ({"pending_decisions": ["公开合同变更待用户决定"]}, "CP_REVIEW_DECISION_REQUIRED")):
            with self.subTest(change=change):
                path.write_text(json.dumps(dict(review, **change)), encoding="utf-8")
                self.expect_failure(diagnostic, "run", "--task", "fixture", "--review", str(path))

    def test_review_cannot_omit_minimum_or_add_unrelated_rule(self):
        path, review = self.review_fixture()
        for refs in (["RULES#文档"], ["PRODUCT#目标", "RULES#文档", "RULES#数据"],
                     ["PRODUCT#目标", "RULES#文档", "RULES#文档"]):
            with self.subTest(refs=refs):
                review["coverage"][0]["refs"] = refs
                path.write_text(json.dumps(review), encoding="utf-8")
                self.expect_failure("CP_REVIEW_COVERAGE", "run", "--task", "fixture", "--review", str(path))

    def test_review_requires_real_current_evidence_and_written_conclusion(self):
        path, review = self.review_fixture()
        for change in ({"evidence": []}, {"conclusion": " "},
                       {"evidence": [{"path": "docs/missing.md", "sha256": "0" * 64}]},
                       {"evidence": [{"path": "docs/rules.md", "sha256": "0" * 64}]},
                       {"evidence": [{"path": "docs/rules.md", "sha256": None}]}):
            with self.subTest(change=change):
                candidate = json.loads(json.dumps(review))
                candidate["coverage"][0].update(change)
                path.write_text(json.dumps(candidate), encoding="utf-8")
                self.expect_failure("CP_REVIEW_EVIDENCE", "run", "--task", "fixture", "--review", str(path))

    def test_agent_review_cannot_satisfy_separate_human_responsibility(self):
        self.catalog["checks"]["major-decision"] = {"kind": "human", "implemented": False}
        self.task["verification"].append({"check": "major-decision", "criterion": "重大边界取舍须人工决定"})
        self.save_task()
        path, _ = self.review_fixture()
        self.expect_failure("CP_UNIMPLEMENTED_CHECK", "run", "--task", "fixture", "--review", str(path))
        self.task["acceptance"] = {"actor": "Agent", "date": "2026-10-12", "source": "WORK#交付",
                                   "functional_sha": "a" * 40, "scope": "普通评审不产生单元验收"}
        self.save_task()
        self.expect_failure("CP_ACCEPTANCE_SOURCE", "check")

    def test_review_registration_cannot_relabel_other_checks_or_attach_commands(self):
        path, _ = self.review_fixture()
        for change in ({"kind": "review", "implemented": True, "argv": ["@python", "-c", "print('passed')"]},
                       {"kind": "review", "implemented": True, "environment_argv": ["@python", "--version"]},
                       {"kind": "review", "implemented": True, "stage": "product", "mechanism_checks": ["doc-integrity"],
                        "reason": "不能延期普通评审"}):
            with self.subTest(change=change):
                self.catalog["checks"]["semantic-review"] = change
                self.save_catalog()
                self.expect_failure("CP_SCHEMA", "check")
        self.catalog["checks"]["semantic-review"] = {"kind": "review", "implemented": True}
        self.catalog["checks"]["database-scenario"] = {"kind": "review", "implemented": True}
        self.save_catalog()
        self.expect_failure("CP_SCHEMA", "run", "--task", "fixture", "--review", str(path))

    def test_review_record_cannot_be_its_own_repository_input(self):
        _, review = self.review_fixture()
        path = self.root / "tools/review.json"
        path.write_text(json.dumps(review), encoding="utf-8")
        self.expect_failure("CP_UNSAFE_PATH", "run", "--task", "fixture", "--review", str(path))

    def test_external_review_and_evidence_changes_during_execution_invalidate_result(self):
        self.catalog["checks"]["after-review"] = {"kind": "automatic", "implemented": True,
            "argv": ["@python", "-c", "pass"]}
        self.task["verification"].append({"check": "after-review", "criterion": "执行前后评审不变"})
        self.save_task()
        path, review = self.review_fixture()
        scenario = path.with_name("scenario.json")
        scenario.write_text('{"scenario":"observed","exit_code":0}', encoding="utf-8")
        review["coverage"][0]["evidence"].append({"path": str(scenario), "sha256": hashlib.sha256(scenario.read_bytes()).hexdigest()})
        # 检查顺序固定：先审阅，再运行该隔离修改器；外部材料不进入仓库快照。
        self.catalog["checks"]["zz-after-review"] = self.catalog["checks"].pop("after-review")
        self.task["verification"][-1]["check"] = "zz-after-review"
        for changed_path in (path, scenario):
            with self.subTest(path=changed_path.name):
                self.catalog["checks"]["zz-after-review"]["argv"] = ["@python", "-c",
                    "from pathlib import Path; p=Path(" + repr(str(changed_path)) + "); p.write_text(p.read_text()+' ')"]
                self.save_catalog()
                self.save_task()
                self.render_indices()
                code, context = self.command("context", "--task", "fixture")
                self.assertEqual(code, 0, context)
                review["input"] = context["input"]
                review["coverage"][0]["evidence"][-1]["sha256"] = hashlib.sha256(scenario.read_bytes()).hexdigest()
                path.write_text(json.dumps(review), encoding="utf-8")
                self.expect_failure("CP_REVIEW_CHANGED", "run", "--task", "fixture", "--review", str(path))

    def test_lightweight_document_task_passes_without_loading_data_rules(self):
        self.render_indices()
        code, output = self.command("check")
        self.assertEqual(code, 0, output)
        code, output = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, output)
        self.assertEqual({item["ref"] for item in output["rules"]}, {"PRODUCT#目标", "RULES#文档"})

    def maintenance_policy(self):
        self.catalog["checks"]["probe-tests"] = {"kind": "automatic", "implemented": True,
                                                 "argv": ["@python", "-c", "print('calibrated')"], "requires_test_count": True}
        self.catalog["maintenance"] = {"inputs": ["tools/probe.py"], "checks": ["probe-tests"]}
        self.catalog["routes"][1]["checks"].append("probe-tests")
        self.save_catalog()

    def test_human_reading_order_is_numbered_without_expanding_agent_minimum(self):
        self.catalog["reading_order"] = ["PRODUCT", "RULES"]
        self.save_catalog()
        code, output = self.command("index")
        self.assertEqual(code, 0, output)
        guide = output["files"]["docs/index.md"]
        self.assertIn("## 人工阅读顺序", guide)
        self.assertIn("1. [01 · PRODUCT · 产品](../README.md)", guide)
        self.assertIn("2. [02 · RULES · 规范](rules.md)", guide)
        self.assertNotIn("03 · WORK", guide)
        code, route = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, route)
        self.assertEqual({item["ref"] for item in route["rules"]}, {"PRODUCT#目标", "RULES#文档"})

    def test_human_reading_order_cannot_omit_duplicate_or_add_unrelated_documents(self):
        for order in (["PRODUCT"], ["PRODUCT", "RULES", "RULES"],
                      ["PRODUCT", "RULES", "WORK"], ["PRODUCT", "MISSING"], "PRODUCT"):
            with self.subTest(order=order):
                self.catalog["reading_order"] = order
                self.save_catalog()
                self.expect_failure("CP_READING_ORDER", "index")

    def test_document_change_omits_unrelated_calibration_not_delivery_responsibility(self):
        self.maintenance_policy()
        self.render_indices()
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertNotIn("probe-tests", plan["required_checks"])
        self.assertEqual(plan["maintenance_checks"], [])
        self.assertEqual([item["id"] for item in plan["not_applicable_checks"]], ["probe-tests"])
        self.assertFalse(plan["not_applicable_checks"][0]["executed"])
        self.write("db/change.sql", "select 1;\n")
        self.expect_failure("CP_UNIMPLEMENTED_CHECK", "run", "--task", "fixture")

    def test_actual_tool_change_and_explicit_calibration_cannot_be_omitted(self):
        self.maintenance_policy()
        self.write("tools/probe.py", "# 校准输入\n")
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertIn("probe-tests", plan["required_checks"])
        self.assertEqual(plan["maintenance_checks"], ["probe-tests"])
        self.assertEqual(plan["not_applicable_checks"], [])
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "tool")
        self.task["base"] = self.git("rev-parse", "HEAD").strip()
        self.task["verification"].append({"check": "probe-tests", "criterion": "本单元显式维护校验器"})
        self.save_task()
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertIn("probe-tests", plan["maintenance_checks"])

    def test_maintenance_policy_cannot_relabel_product_or_human_obligations(self):
        self.maintenance_policy()
        for policy in ({"inputs": ["../outside"], "checks": ["probe-tests"]},
                       {"inputs": [], "checks": ["probe-tests"]},
                       {"inputs": ["tools/probe.py"], "checks": ["database-scenario"]},
                       {"inputs": ["tools/probe.py"], "checks": ["doc-integrity"]},
                       {"inputs": ["tools/probe.py"], "checks": ["missing"]},
                       {"inputs": ["tools/probe.py"], "checks": ["probe-tests", "probe-tests"]}):
            with self.subTest(policy=policy):
                self.catalog["maintenance"] = policy
                self.save_catalog()
                self.expect_failure("CP_MAINTENANCE_POLICY", "route", "--task", "fixture")

    def test_context_returns_exact_sections_without_unrelated_history(self):
        self.write("docs/rules.md", "# 规范\n\n## 文档\n\n只引用唯一正文。\n\n```text\n## 数据\n围栏中的标题不是边界。\n```\n\n## 数据\n\n不相关数据细节。\n")
        self.save_task()
        with (self.root / "docs/tasks/task.md").open("a", encoding="utf-8") as stream:
            stream.write("\n## 历史执行\n\n不相关历史运行详情。\n")
        code, output = self.command("context", "--task", "fixture", "--ref", "RULES#文档")
        self.assertEqual(code, 0, output)
        text = json.dumps(output, ensure_ascii=False)
        self.assertIn("只引用唯一正文", text)
        self.assertIn("围栏中的标题不是边界", text)
        self.assertNotIn("不相关数据细节", text)
        self.assertNotIn("不相关历史运行详情", text)
        self.assertEqual(output["task_brief"]["frontier"], self.task["frontier"])
        self.assertFalse(output["semantic_review_proven"])
        self.assertTrue(all(item["start_line"] <= item["end_line"] for item in output["sections"]))

    def test_context_merges_overlapping_sections_without_losing_rule_bindings(self):
        self.catalog["routes"][1]["refs"] += ["RULES#规范"]
        self.save_catalog()
        code, output = self.command("context", "--task", "fixture", "--ref", "RULES#规范", "--ref", "RULES#文档")
        self.assertEqual(code, 0, output)
        sections = [item for item in output["sections"] if item["path"] == "docs/rules.md"]
        self.assertEqual(len(sections), 1)
        self.assertEqual(set(sections[0]["refs"]), {"RULES#规范", "RULES#文档"})
        self.assertEqual(sections[0]["text"].count("只引用唯一正文"), 1)
        refs = {item["ref"] for item in output["rules"]}
        self.assertTrue({"RULES#规范", "RULES#文档"}.issubset(refs))

    def test_context_recomputes_scope_after_real_change_and_rejects_unknown_files(self):
        self.write("db/change.sql", "select 1;\n")
        code, output = self.command("context", "--task", "fixture", "--ref", "RULES#数据")
        self.assertEqual(code, 0, output)
        self.assertIn("RULES#数据", {item["ref"] for item in output["rules"]})
        self.assertIn("database-scenario", output["required_checks"])
        self.write("unknown.bin", "unknown\n")
        self.expect_failure("CP_UNKNOWN_FILE", "context", "--task", "fixture")

    def test_context_includes_current_design_contract_not_only_generic_rules(self):
        self.catalog["documents"]["DESIGN"] = {"path": "docs/design/current.md", "category": "standards", "kind": "design"}
        self.write("docs/design/current.md", "# 当前设计\n\n## 合同\n\n本用例必须遵循已确认失败语义。\n\n## 其他\n\n无关实现细节。\n")
        self.task["design_refs"].append("DESIGN#合同")
        self.save_catalog()
        self.save_task()
        code, output = self.command("context", "--task", "fixture", "--ref", "DESIGN#合同")
        self.assertEqual(code, 0, output)
        text = json.dumps(output, ensure_ascii=False)
        self.assertIn("本用例必须遵循已确认失败语义", text)
        self.assertNotIn("无关实现细节", text)

    def test_context_default_is_manifest_not_all_required_bodies(self):
        code, output = self.command("context", "--task", "fixture")
        self.assertEqual(code, 0, output)
        self.assertEqual(output["sections"], [])
        self.assertEqual(output["loaded_refs"], [])
        self.assertEqual(set(output["remaining_refs"]), {"PRODUCT#目标", "RULES#文档"})
        self.assertNotIn("只引用唯一正文", json.dumps(output, ensure_ascii=False))
        self.assertIn("doc-integrity", output["required_checks"])

    def test_partial_context_keeps_unloaded_rules_and_all_verification_responsibilities(self):
        self.expect_failure("CP_CONTEXT_SELECTION", "context", "--task", "fixture", "--ref", "RULES#数据")
        self.write("db/change.sql", "select 1;\n")
        code, output = self.command("context", "--task", "fixture", "--ref", "RULES#文档")
        self.assertEqual(code, 0, output)
        self.assertEqual(output["loaded_refs"], ["RULES#文档"])
        self.assertIn("RULES#数据", output["remaining_refs"])
        self.assertIn("database-scenario", output["required_checks"])
        self.assertFalse(output["semantic_review_proven"])

    def test_ci_plan_uses_event_diff_not_long_running_task_history(self):
        self.maintenance_policy()
        self.render_indices()
        code, output = self.command("ci-plan", "--base", self.task["base"])
        self.assertEqual(code, 0, output)
        self.assertFalse(output["maintenance_required"])
        self.write("tools/probe.py", "# 维护校验器\n")
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "calibration")
        base = self.git("rev-parse", "HEAD").strip()
        self.write("docs/tasks/task.md", (self.root / "docs/tasks/task.md").read_text(encoding="utf-8") + "\n过程说明补充，不修改有效规则。\n")
        code, output = self.command("ci-plan", "--base", base)
        self.assertEqual(code, 0, output)
        self.assertFalse(output["maintenance_required"], "历史工具变更不能让普通提交永远执行校准")
        self.git("mv", "tools/probe.py", "tools/renamed.py")
        code, output = self.command("ci-plan", "--base", base)
        self.assertEqual(code, 0, output)
        self.assertTrue(output["maintenance_required"], "重命名前的工具输入不能被遗漏")

    def test_ci_plan_selects_wired_product_and_shared_inputs_but_not_plain_docs(self):
        self.catalog["checks"]["product-probe"] = {
            "kind": "automatic", "implemented": True, "stage": "product",
            "mechanism_checks": ["doc-integrity"], "reason": "隔离选择合同，非产品执行",
            "argv": ["@python", "-c", "print('fixture')"]}
        self.catalog["product_ci"] = {"inputs": ["tools/product_gate/**"], "checks": ["product-probe"]}
        self.save_catalog()
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "--quiet", "-m", "wire simulated product")
        base = self.git("rev-parse", "HEAD").strip()
        for path in ("db/change.sql", "tools/product_gate/verify.py"):
            self.write(path, "fixture\n")
            code, result = self.command("ci-plan", "--base", base)
            self.assertEqual(code, 0, result)
            self.assertTrue(result["product_required"])
            self.assertEqual(result["product_checks"], ["product-probe"])
            self.write("unknown/late.input", "must still be classified\n")
            self.expect_failure("CP_UNKNOWN_FILE", "ci-plan", "--base", base)
            (self.root / "unknown/late.input").unlink()
            (self.root / path).unlink()
        self.write("docs/notes.md", "# 普通说明\n")
        code, result = self.command("ci-plan", "--base", base)
        self.assertEqual(code, 0, result)
        self.assertFalse(result["product_required"])

    def test_ci_plan_rejects_unwired_product_inputs_instead_of_document_only_green(self):
        self.maintenance_policy()
        self.render_indices()
        self.write("db/change.sql", "select 1;\n")
        self.expect_failure("CP_CI_PRODUCT_UNWIRED", "ci-plan", "--base", self.task["base"])

    def test_ci_plan_bootstrap_includes_all_tracked_inputs(self):
        self.maintenance_policy()
        self.write("tools/probe.py", "# 已有校准输入\n")
        self.render_indices()
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "inputs")
        code, output = self.command("ci-plan", "--base", "0" * 40)
        self.assertEqual(code, 0, output)
        self.assertTrue(output["maintenance_required"])
        self.assertFalse(output["human_acceptance"])

    def test_receipt_cannot_rewrite_calibration_applicability(self):
        self.maintenance_policy()
        self.render_indices()
        code, report = self.command("run", "--task", "fixture")
        self.assertEqual(code, 0, report)
        temp = tempfile.TemporaryDirectory(prefix="domain-forge-classification-receipt-")
        self.addCleanup(temp.cleanup)
        receipt = pathlib.Path(temp.name) / "report.json"
        receipt.write_text(json.dumps(report), encoding="utf-8")
        code, result = self.command("verify-receipt", "--task", "fixture", "--receipt", str(receipt))
        self.assertEqual(code, 0, result)
        for key, value in (("maintenance_checks", ["probe-tests"]), ("not_applicable_checks", [])):
            changed = dict(report, **{key: value})
            receipt.write_text(json.dumps(changed), encoding="utf-8")
            self.expect_failure("CP_MAINTENANCE_COVERAGE", "verify-receipt", "--task", "fixture", "--receipt", str(receipt))

    def test_four_task_types_route_to_their_registered_scope(self):
        for task_type, scope in [("create-project", "bootstrap"), ("business-demand", "requirements"),
                                 ("platform-extension", "extension"), ("bugfix", "rework")]:
            with self.subTest(task_type=task_type):
                self.task["type"] = task_type
                self.save_task()
                code, output = self.command("route", "--task", "fixture")
                self.assertEqual(code, 0, output)
                self.assertIn(scope, output["scopes"])

    def test_real_untracked_sql_expands_scope_and_deduplicates_rules(self):
        self.write("db/change.sql", "select 1;\n")
        code, output = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, output)
        self.assertIn("persistence", output["scopes"])
        self.assertIn("RULES#数据", [item["ref"] for item in output["rules"]])
        refs = [item["ref"] for item in output["rules"]]
        self.assertEqual(len(refs), len(set(refs)))
        reasons = output.get("scope_reasons", {}).get("persistence", [])
        self.assertTrue(any("db/change.sql" in reason for reason in reasons))
        self.assertIn("scope:persistence", next(item["reasons"] for item in output["rules"] if item["ref"] == "RULES#数据"))

    def test_staged_sql_is_not_hidden_by_document_task_type(self):
        self.write("db/staged.sql", "select 1;\n")
        self.git("add", "db/staged.sql")
        code, output = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, output)
        self.assertIn("persistence", output["scopes"])

    def test_deleted_and_renamed_paths_still_contribute_scope(self):
        self.write("db/original.sql", "select 1;\n")
        self.git("add", "db/original.sql")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "sql")
        self.task["base"] = self.git("rev-parse", "HEAD").strip()
        self.save_task()
        self.git("mv", "db/original.sql", "docs/renamed.md")
        code, output = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, output)
        self.assertIn("persistence", output["scopes"])

    def test_unknown_file_cannot_pass_with_empty_scope(self):
        self.write("unknown.custom", "not classified\n")
        self.expect_failure("CP_UNKNOWN_FILE", "route", "--task", "fixture")

    def test_conflicting_file_purpose_is_reported(self):
        self.catalog["classifiers"].append({"glob": "docs/tasks/*", "purpose": "production", "scopes": ["persistence"]})
        self.save_catalog()
        self.expect_failure("CP_CONFLICTING_PURPOSE", "route", "--task", "fixture")

    def test_unknown_scope_and_missing_route_are_distinct_failures(self):
        self.task["scopes"] = ["unregistered"]
        self.save_task()
        self.expect_failure("CP_UNKNOWN_SCOPE", "route", "--task", "fixture")
        self.task["scopes"] = ["persistence"]
        self.save_task()
        self.catalog["routes"] = [item for item in self.catalog["routes"] if item["id"] != "data"]
        self.save_catalog()
        self.expect_failure("CP_MISSING_ROUTE", "route", "--task", "fixture")

    def test_missing_document_registration_is_rejected(self):
        self.render_indices()
        self.write("docs/missed.md", "# 漏登记\n")
        self.expect_failure("CP_UNINDEXED_DOCUMENT", "check")

    def test_invalid_section_fails_then_corrected_reference_passes(self):
        self.render_indices()
        self.catalog["routes"][1]["refs"] = ["RULES#不存在"]
        self.save_catalog()
        self.expect_failure("CP_INVALID_REFERENCE", "check")
        self.catalog["routes"][1]["refs"] = ["RULES#文档"]
        self.save_catalog()
        code, output = self.command("check")
        self.assertEqual(code, 0, output)

    def test_config_reference_fails_for_unknown_section_then_corrected_passes(self):
        self.catalog["reference_inputs"] = {"tools/baseline.json": ["decision_ref"]}
        self.save_catalog()
        self.write("tools/baseline.json", '{"decision_ref":"RULES#文档"}')
        self.render_indices()
        self.write("tools/baseline.json", '{"decision_ref":"RULES#不存在"}')
        self.expect_failure("CP_INVALID_REFERENCE", "check")
        self.write("tools/baseline.json", '{"decision_ref":"RULES#文档"}')
        code, output = self.command("check")
        self.assertEqual(code, 0, output)

    def test_config_reference_missing_file_or_field_is_rejected(self):
        self.catalog["reference_inputs"] = {"tools/baseline.json": ["decision_ref"]}
        self.save_catalog()
        self.write("tools/baseline.json", '{"decision_ref":"RULES#文档"}')
        self.render_indices()
        (self.root / "tools/baseline.json").unlink()
        self.expect_failure("CP_INPUT_UNREADABLE", "check")
        self.write("tools/baseline.json", '{}')
        self.expect_failure("CP_SCHEMA", "check")
        self.write("tools/baseline.json", '{"decision_ref":[]}')
        self.expect_failure("CP_SCHEMA", "check")

    def test_config_reference_registration_rejects_unsafe_path_or_duplicate_fields(self):
        self.render_indices()
        self.catalog["reference_inputs"] = {"../outside.json": ["decision_ref"]}
        self.save_catalog()
        self.expect_failure("CP_UNSAFE_PATH", "check")
        self.catalog["reference_inputs"] = {"tools/baseline.json": ["decision_ref", "decision_ref"]}
        self.save_catalog()
        self.write("tools/baseline.json", '{"decision_ref":"RULES#文档"}')
        self.expect_failure("CP_SCHEMA", "check")

    def test_unrouted_rule_section_is_rejected(self):
        self.render_indices()
        self.write("docs/rules.md", (self.root / "docs/rules.md").read_text(encoding="utf-8") + "\n## 新强规则\n\n不能遗漏。\n")
        self.expect_failure("CP_UNROUTED_SECTION", "check")

    def test_rule_without_verification_binding_is_rejected(self):
        self.render_indices()
        self.catalog["routes"][1]["checks"] = []
        self.save_catalog()
        self.expect_failure("CP_MISSING_VERIFICATION", "check")

    def test_wrong_task_pointer_and_missing_goal_are_rejected(self):
        self.render_indices()
        self.catalog["current_task"] = "wrong"
        self.save_catalog()
        self.expect_failure("CP_TASK_POINTER", "check")
        self.catalog["current_task"] = "fixture"
        self.save_catalog()
        self.task["goals"] = []
        self.save_task()
        self.expect_failure("CP_GOAL_MISSING", "check")

    def test_invalid_goal_and_duplicate_task_fact_are_rejected(self):
        self.render_indices()
        self.task["goals"] = ["PRODUCT#不存在"]
        self.save_task()
        self.expect_failure("CP_INVALID_REFERENCE", "check")
        self.task["goals"] = ["PRODUCT#目标"]
        self.save_task()
        path = self.root / "docs/tasks/task.md"
        self.write("docs/tasks/task.md", path.read_text(encoding="utf-8") + "\n```control-plane-task\n{}\n```\n")
        self.expect_failure("CP_TASK_RECORD_COUNT", "check")

    def test_generated_index_cannot_be_hand_edited_into_another_state(self):
        self.render_indices()
        self.write("docs/tasks/index.md", "# 自称已验收\n")
        self.expect_failure("CP_INDEX_DRIFT", "check")

    def test_acceptance_requires_human_source_and_exact_functional_sha(self):
        self.render_indices()
        self.task["acceptance"] = {"actor": "Agent", "functional_sha": "passed"}
        self.save_task()
        self.expect_failure("CP_ACCEPTANCE_SOURCE", "check")

    def test_accepted_task_with_failed_closing_remains_pending_and_archivable(self):
        self.task["acceptance"] = {"actor": "用户", "date": "2026-10-08", "source": "WORK#交付",
                                   "functional_sha": "a" * 40, "scope": "夹具路由单元"}
        self.task["closing"] = {"status": "failed", "reason": "CI 尚未核对"}
        self.save_task()
        code, output = self.command("index")
        self.assertEqual(code, 0, output)
        index = output["files"]["docs/tasks/index.md"]
        self.assertIn("收尾待处理", index)
        self.assertIn("归档", index)
        self.assertIn("CI 尚未核对", index)

    def test_rule_and_goal_reference_cannot_escape_repository(self):
        self.catalog["documents"]["PRODUCT"]["path"] = "../outside.md"
        self.save_catalog()
        self.expect_failure("CP_UNSAFE_PATH", "check")

    def test_current_input_receipt_is_structural_evidence_not_acceptance(self):
        self.render_indices()
        code, output = self.command("run", "--task", "fixture")
        self.assertEqual(code, 0, output)
        self.assertFalse(output["human_acceptance"])
        receipt = self.root.parent / (self.root.name + "-receipt.json")
        self.addCleanup(lambda: receipt.unlink(missing_ok=True))
        receipt.write_text(json.dumps(output), encoding="utf-8")
        code, checked = self.command("verify-receipt", "--task", "fixture", "--receipt", str(receipt))
        self.assertEqual(code, 0, checked)
        self.assertEqual(checked["proof_scope"], "record-integrity-only")
        self.write("README.md", (self.root / "README.md").read_text(encoding="utf-8") + "\n输入变化。\n")
        self.expect_failure("CP_STALE_INPUT", "verify-receipt", "--task", "fixture", "--receipt", str(receipt))

    def test_missing_execution_in_receipt_is_detected(self):
        self.render_indices()
        code, output = self.command("run", "--task", "fixture")
        self.assertEqual(code, 0, output)
        output["checks"] = []
        receipt = self.root.parent / (self.root.name + "-receipt.json")
        self.addCleanup(lambda: receipt.unlink(missing_ok=True))
        receipt.write_text(json.dumps(output), encoding="utf-8")
        self.expect_failure("CP_MISSING_EXECUTION", "verify-receipt", "--task", "fixture", "--receipt", str(receipt))

    def test_missing_real_scenario_never_becomes_delivery_pass(self):
        self.render_indices()
        self.write("db/change.sql", "select 1;\n")
        self.expect_failure("CP_UNIMPLEMENTED_CHECK", "run", "--task", "fixture")

    def product_responsibility(self):
        """隔离产品结果与当前机制；故意失败的结果不得在控制面夹具里执行。"""
        self.catalog["checks"]["product-result"] = {
            "kind": "automatic", "implemented": True, "stage": "product",
            "mechanism_checks": ["doc-integrity"], "reason": "产品形成后才执行实际结果",
            "argv": ["@python", "-c", "raise SystemExit(7)"]}
        self.catalog["routes"][1]["checks"].append("product-result")
        self.catalog["task_types"]["governance"] = ["documentation"]
        self.catalog["classifiers"].append({"glob": "product/*", "purpose": "production", "scopes": ["documentation"]})
        self.save_catalog()

    def test_control_plane_defers_product_result_without_claiming_execution(self):
        self.product_responsibility()
        self.render_indices()
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertEqual(plan.get("verification_phase"), "control-plane")
        self.assertEqual(plan["required_checks"], ["doc-integrity"])
        later = plan.get("deferred_checks", [])
        self.assertEqual([item["id"] for item in later], ["product-result"])
        self.assertEqual(later[0]["status"], "not_executed")
        self.assertFalse(later[0]["executed"])
        code, report = self.command("run", "--task", "fixture")
        self.assertEqual(code, 0, report)
        self.assertEqual([item["id"] for item in report["checks"]], ["doc-integrity"])
        self.assertEqual(report["deferred_checks"], later)
        self.assertFalse(report["human_acceptance"])

    def test_missing_or_failing_mechanism_still_blocks_control_plane(self):
        self.product_responsibility()
        self.catalog["checks"]["mechanism"] = {"kind": "automatic", "implemented": False}
        self.catalog["checks"]["product-result"]["mechanism_checks"] = ["doc-integrity", "mechanism"]
        self.save_catalog()
        self.render_indices()
        code, report = self.command("run", "--task", "fixture")
        self.assertNotEqual(code, 0, report)
        self.assertIn("mechanism", report["required_checks"])
        self.assertTrue(any(item["code"] == "CP_UNIMPLEMENTED_CHECK" and "mechanism" in item["message"]
                            for item in report["diagnostics"]), report)
        self.catalog["checks"]["mechanism"].update(implemented=True, argv=["@python", "-c", "raise SystemExit(9)"])
        self.save_catalog()
        code, report = self.command("run", "--task", "fixture")
        self.assertNotEqual(code, 0, report)
        self.assertIn("CP_CHECK_FAILED", [item["code"] for item in report["diagnostics"]])
        self.assertEqual(next(item["exit_code"] for item in report["checks"] if item["id"] == "mechanism"), 9)
        self.catalog["checks"]["mechanism"]["argv"] = ["@python", "-c", "raise SystemExit(0)"]
        self.save_catalog()
        code, report = self.command("run", "--task", "fixture")
        self.assertEqual(code, 0, report)

    def test_actual_product_changes_cannot_hide_behind_governance_type(self):
        self.product_responsibility()
        self.task["type"] = "governance"
        self.save_task()
        self.render_indices()
        self.write("product/input.txt", "actual product input\n")
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertEqual(plan.get("verification_phase"), "product")
        self.assertIn("product-result", plan["required_checks"])
        self.assertEqual(plan.get("deferred_checks"), [])
        code, report = self.command("run", "--task", "fixture")
        self.assertNotEqual(code, 0, report)
        self.assertEqual(next(item["exit_code"] for item in report["checks"] if item["id"] == "product-result"), 7)
        self.catalog["checks"]["product-result"]["argv"] = ["@python", "-c",
            f"import sys,json;sys.path.insert(0,{str(ENTRY.parent)!r});from control_plane import input_snapshot;"
            "from pathlib import Path;print(json.dumps({'ok':True,'input':input_snapshot(Path.cwd())[0],"
            "'proof_scope':'minimal-engineering-real-product','checks':{'product-result':{'ok':True}}}))"]
        self.save_catalog()
        code, report = self.command("run", "--task", "fixture")
        self.assertEqual(code, 0, report)
        self.assertEqual(report["deferred_checks"], [])

    def test_test_inputs_and_functional_task_types_require_product_results(self):
        self.product_responsibility()
        self.catalog["classifiers"][-1]["purpose"] = "test"
        self.save_catalog()
        self.write("product/test-input.txt", "actual test input\n")
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertIn("product-result", plan["required_checks"])
        (self.root / "product/test-input.txt").unlink()
        self.task["type"] = "business-demand"
        self.save_task()
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertEqual(plan.get("verification_phase"), "product")
        self.assertIn("product-result", plan["required_checks"])

    def test_product_bundle_executes_once_per_run_and_rejects_stale_or_partial_proof(self):
        self.product_responsibility()
        self.write("product/input.txt", "real route input\n")
        counter = pathlib.Path(self.temp.name).parent / (self.root.name + "-count")
        self.addCleanup(lambda: counter.unlink(missing_ok=True))
        original = (f"import sys,json;sys.path.insert(0,{str(ENTRY.parent)!r});from control_plane import input_snapshot;"
                    f"from pathlib import Path;p=Path({str(counter)!r});p.write_text(p.read_text()+'1' if p.exists() else '1');"
                    "print(json.dumps({'ok':True,'input':input_snapshot(Path.cwd())[0],"
                    "'proof_scope':'minimal-engineering-real-product',"
                    "'checks':{'product-result':{'ok':True},'product-second':{'ok':True}}}))")
        self.catalog["checks"]["product-second"] = dict(self.catalog["checks"]["product-result"])
        self.catalog["routes"][1]["checks"].append("product-second")
        for code, error in ((original, None),
                            (original.replace("input_snapshot(Path.cwd())[0]", "'stale'"), "CP_PRODUCT_EVIDENCE"),
                            (original.replace("'product-second':{'ok':True}", "'product-second':{'ok':False}"), "CP_PRODUCT_EVIDENCE")):
            for name in ("product-result", "product-second"):
                self.catalog["checks"][name]["argv"] = ["@python", "-c", code]
            self.save_catalog()
            self.render_indices()
            before = len(counter.read_text()) if counter.exists() else 0
            status, report = self.command("run", "--task", "fixture")
            self.assertEqual(len(counter.read_text()), before + 1, "同次固定组合只执行一次，下一次必须重新执行")
            self.assertEqual(status, 0 if error is None else 1, report)
            if error:
                self.assertIn(error, {item["code"] for item in report["diagnostics"]})

    def test_product_staged_committed_deleted_and_renamed_paths_keep_responsibility(self):
        self.product_responsibility()
        self.write("product/input.txt", "actual product input\n")
        self.git("add", "product/input.txt")
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertIn("product-result", plan["required_checks"])
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "product")
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertIn("product-result", plan["required_checks"])
        self.task["base"] = self.git("rev-parse", "HEAD").strip()
        self.save_task()
        self.git("mv", "product/input.txt", "docs/renamed.md")
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)
        self.assertIn("product/input.txt", plan["changed_paths"])
        self.assertIn("product-result", plan["required_checks"])

    def test_product_policy_cannot_defer_missing_mechanism_or_unknown_purpose(self):
        self.product_responsibility()
        original = dict(self.catalog["checks"]["product-result"])
        mutations = [{"stage": "future"}, {"mechanism_checks": []},
                     {"mechanism_checks": ["missing"]}, {"mechanism_checks": ["product-result"]},
                     {"mechanism_checks": ["doc-integrity", "doc-integrity"]}, {"reason": ""}]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.catalog["checks"]["product-result"] = dict(original, **mutation)
                self.save_catalog()
                self.expect_failure("CP_STAGE_POLICY", "route", "--task", "fixture")
        self.catalog["checks"]["product-result"] = original
        self.catalog["checks"]["doc-integrity"]["stage"] = "product"
        self.catalog["checks"]["doc-integrity"]["mechanism_checks"] = ["product-result"]
        self.catalog["checks"]["doc-integrity"]["reason"] = "循环延期不能放行"
        self.save_catalog()
        self.expect_failure("CP_STAGE_POLICY", "route", "--task", "fixture")
        self.catalog["checks"]["doc-integrity"].pop("stage")
        self.catalog["checks"]["doc-integrity"].pop("mechanism_checks")
        self.catalog["classifiers"][-1]["purpose"] = "prodution"
        self.save_catalog()
        self.expect_failure("CP_SCHEMA", "route", "--task", "fixture")
        self.catalog["classifiers"][-1]["purpose"] = "production"
        self.save_catalog()
        code, plan = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, plan)

    def test_receipt_cannot_hide_or_promote_deferred_product_results(self):
        self.product_responsibility()
        self.render_indices()
        code, report = self.command("run", "--task", "fixture")
        self.assertEqual(code, 0, report)
        receipt = self.root.parent / (self.root.name + "-stage-receipt.json")
        self.addCleanup(lambda: receipt.unlink(missing_ok=True))
        for mutation in ({"verification_phase": "product"}, {"deferred_checks": []},
                         {"deferred_checks": [{"id": "product-result", "status": "passed", "executed": True}]}):
            with self.subTest(mutation=mutation):
                receipt.write_text(json.dumps(dict(report, **mutation)), encoding="utf-8")
                self.expect_failure("CP_STAGE_COVERAGE", "verify-receipt", "--task", "fixture", "--receipt", str(receipt))
        receipt.write_text(json.dumps(report), encoding="utf-8")
        code, checked = self.command("verify-receipt", "--task", "fixture", "--receipt", str(receipt))
        self.assertEqual(code, 0, checked)

    def test_deferred_product_result_does_not_hide_missing_mechanism_environment(self):
        self.product_responsibility()
        self.catalog["checks"]["doc-integrity"]["environment_argv"] = ["@python", "-c", "raise SystemExit(8)"]
        self.save_catalog()
        self.render_indices()
        self.expect_failure("CP_ENVIRONMENT_INPUT", "run", "--task", "fixture")

    def test_invalid_anchor_link_is_rejected_without_fetching_external_links(self):
        self.render_indices()
        self.write("docs/rules.md", (self.root / "docs/rules.md").read_text(encoding="utf-8") + "\n[坏引用](../README.md#不存在)\n")
        self.expect_failure("CP_LINK_ANCHOR", "check")

    def test_active_task_cannot_lose_current_pointer(self):
        self.render_indices()
        self.catalog["current_task"] = None
        self.save_catalog()
        self.expect_failure("CP_TASK_POINTER", "check")

    def test_duplicate_document_id_for_same_normalized_path_is_rejected(self):
        self.catalog["documents"]["COPY"] = {"path": "./README.md", "category": "product", "kind": "rules"}
        self.save_catalog()
        self.expect_failure("CP_UNSAFE_PATH", "check")

    def test_index_generator_does_not_overwrite_manual_text_or_git_metadata(self):
        self.write("docs/product-index.md", "# 人工设计\n")
        self.expect_failure("CP_INDEX_OWNERSHIP", "index", "--write")
        self.assertEqual((self.root / "docs/product-index.md").read_text(encoding="utf-8"), "# 人工设计\n")
        self.catalog["categories"]["product"]["index"] = ".git/forbidden.md"
        self.save_catalog()
        self.expect_failure("CP_UNSAFE_PATH", "index", "--write")
        self.assertFalse((self.root / ".git/forbidden.md").exists())

    def test_duplicate_json_key_does_not_silently_replace_fact(self):
        self.write("tools/control_plane/catalog.json", '{"schema": 1, "schema": 2}\n')
        self.expect_failure("CP_DUPLICATE_KEY", "check")

    def test_replacement_cycle_cannot_become_current_rule(self):
        self.catalog["documents"]["PRODUCT"]["replaced_by"] = "RULES"
        self.catalog["documents"]["RULES"]["replaced_by"] = "PRODUCT"
        self.save_catalog()
        self.expect_failure("CP_REPLACEMENT", "check")

    def test_zero_or_skipped_required_tests_are_not_successful_execution(self):
        (self.root / "empty-suite").mkdir()
        self.catalog["checks"]["required-suite"] = {"kind": "automatic", "implemented": True,
            "requires_test_count": True, "argv": ["@python", "-B", "-m", "unittest", "discover", "-s", "empty-suite"]}
        self.task["verification"].append({"check": "required-suite", "criterion": "必须实际执行"})
        self.save_catalog()
        self.save_task()
        self.render_indices()
        self.expect_failure("CP_EMPTY_EXECUTION", "run", "--task", "fixture")
        self.write("tools/skipped_fixture.py", "import unittest\n\nclass RequiredSuite(unittest.TestCase):\n"
                   "    @unittest.skip('隔离验证：必需场景被跳过')\n    def test_required_scenario(self):\n"
                   "        self.assertTrue(True)\n\nunittest.main()\n")
        self.catalog["checks"]["required-suite"]["argv"] = ["@python", "-B", "tools/skipped_fixture.py"]
        self.save_catalog()
        self.expect_failure("CP_SKIPPED_EXECUTION", "run", "--task", "fixture")

    def test_input_changed_during_real_execution_invalidates_result(self):
        # 只修改自有夹具输入，用于证明运行前后字节核对而不是报告字段自述。
        self.catalog["checks"]["mutating-fixture"] = {"kind": "automatic", "implemented": True,
            "argv": ["@python", "-c", "from pathlib import Path; p=Path('README.md'); p.write_text(p.read_text()+'\\n变更。\\n')"]}
        self.task["verification"].append({"check": "mutating-fixture", "criterion": "变化即失效"})
        self.save_catalog()
        self.save_task()
        self.render_indices()
        self.expect_failure("CP_INPUT_CHANGED", "run", "--task", "fixture")

    def test_failed_configured_check_does_not_disappear_from_result(self):
        self.catalog["checks"]["failing-fixture"] = {"kind": "automatic", "implemented": True,
            "argv": ["@python", "-c", "raise SystemExit(7)"]}
        self.task["verification"].append({"check": "failing-fixture", "criterion": "失败阻断"})
        self.save_catalog()
        self.save_task()
        self.render_indices()
        code, report = self.command("run", "--task", "fixture")
        self.assertNotEqual(code, 0)
        self.assertIn("CP_CHECK_FAILED", [item["code"] for item in report["diagnostics"]])
        self.assertEqual(next(item["exit_code"] for item in report["checks"] if item["id"] == "failing-fixture"), 7)

    def test_symlinked_input_cannot_escape_classification_or_snapshot(self):
        self.catalog["documents"]["PRODUCT"]["path"] = "docs/alias.md"
        (self.root / "docs/alias.md").symlink_to(self.root / "README.md")
        self.save_catalog()
        self.expect_failure("CP_UNSAFE_PATH", "check")

    def test_table_column_drift_is_detected(self):
        self.render_indices()
        self.write("docs/rules.md", (self.root / "docs/rules.md").read_text(encoding="utf-8") +
                   "\n| 项目 | 结果 |\n|---|---|\n| 漏列 |\n")
        self.expect_failure("CP_TABLE_COLUMNS", "check")

    def test_accepted_history_survives_rule_replacement_without_becoming_new_task_input(self):
        self.catalog["documents"]["CURRENT-RULES"] = {"path": "docs/current-rules.md", "category": "standards", "kind": "rules"}
        self.write("docs/current-rules.md", "# 新规范\n\n## 文档\n\n只维护当前规则。\n\n## 数据\n\n核对真实持久化。\n")
        self.catalog["documents"]["RULES"]["replaced_by"] = "CURRENT-RULES"
        for route in self.catalog["routes"]:
            route["refs"] = [ref.replace("RULES#", "CURRENT-RULES#") for ref in route["refs"]]
        self.task["acceptance"] = {"actor": "用户", "date": "2026-10-08", "source": "WORK#交付",
                                   "functional_sha": "a" * 40, "scope": "历史夹具单元"}
        self.task["closing"] = {"status": "passed", "record_sha": "b" * 40, "remote_sha": "b" * 40,
                                "ci_sha": "b" * 40, "source": "WORK#交付"}
        self.catalog["current_task"] = None
        self.save_catalog()
        self.save_task()
        self.render_indices()
        code, output = self.command("check")
        self.assertEqual(code, 0, output)
        self.task["acceptance"] = None
        self.task["closing"] = None
        self.catalog["current_task"] = "fixture"
        self.save_catalog()
        self.save_task()
        self.expect_failure("CP_REPLACED_REFERENCE", "check")

    def test_unknown_check_cannot_fill_verification_field(self):
        self.catalog["routes"][1]["checks"] = ["imaginary-check"]
        self.save_catalog()
        self.expect_failure("CP_MISSING_VERIFICATION", "check")

    def test_accepted_closed_task_is_archived_but_outstanding_issue_stays_visible(self):
        self.task["acceptance"] = {"actor": "用户", "date": "2026-10-08", "source": "WORK#交付",
                                   "functional_sha": "a" * 40, "scope": "历史夹具单元"}
        self.task["closing"] = {"status": "passed", "record_sha": "b" * 40, "remote_sha": "b" * 40,
                                "ci_sha": "b" * 40, "source": "WORK#交付"}
        self.task["pending"] = ["另有待解决的合法问题"]
        self.save_task()
        code, output = self.command("index")
        self.assertEqual(code, 0, output)
        self.assertIn("另有待解决的合法问题", output["files"]["docs/tasks/index.md"])
        self.task["pending"] = []
        self.save_task()
        self.expect_failure("CP_TASK_POINTER", "check")

    def test_index_generation_is_idempotent_and_unchanged_input_needs_no_rewrite(self):
        code, output = self.command("index", "--write")
        self.assertEqual(code, 0, output)
        self.assertTrue(output["generated"])
        code, output = self.command("index", "--write")
        self.assertEqual(code, 0, output)
        self.assertEqual(output["generated"], [])

    def test_generated_indices_have_one_terminal_newline_with_or_without_history(self):
        self.catalog["categories"]["tasks"]["archive"] = "docs/tasks/archive.md"
        self.catalog["categories"]["design"] = {"title": "设计", "index": "docs/design/index.md"}
        for history in (False, True):
            with self.subTest(history=history):
                if history:
                    self.catalog["documents"]["EVIDENCE"] = {"path": "docs/tasks/evidence.md",
                        "category": "tasks", "kind": "evidence"}
                    self.write("docs/tasks/evidence.md", "# 证据\n\n## 结果\n\n只作按需输入。\n")
                self.save_catalog()
                code, output = self.command("index")
                self.assertEqual(code, 0, output)
                for relative, content in output["files"].items():
                    self.assertTrue(content.endswith("\n"), relative)
                    self.assertFalse(content.endswith("\n\n"), relative)

    def test_short_task_can_add_semantic_reference_but_cannot_remove_minimum_rules(self):
        self.task["extra_refs"] = ["RULES#数据"]
        self.save_task()
        code, output = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, output)
        self.assertEqual({item["ref"] for item in output["rules"]}, {"PRODUCT#目标", "RULES#文档", "RULES#数据"})
        self.assertIn("database-scenario", output["required_checks"])

    def test_receipt_from_another_runtime_is_not_current_evidence(self):
        self.render_indices()
        code, output = self.command("run", "--task", "fixture")
        self.assertEqual(code, 0, output)
        output["environment"]["python"] = "other runtime"
        receipt = self.root.parent / (self.root.name + "-receipt.json")
        self.addCleanup(lambda: receipt.unlink(missing_ok=True))
        receipt.write_text(json.dumps(output), encoding="utf-8")
        self.expect_failure("CP_STALE_ENVIRONMENT", "verify-receipt", "--task", "fixture", "--receipt", str(receipt))

    def test_external_tool_environment_change_invalidates_otherwise_current_receipt(self):
        environment_file = self.root.parent / (self.root.name + "-external-tool.json")
        self.addCleanup(lambda: environment_file.unlink(missing_ok=True))
        environment_file.write_text('{"ok":true,"environment":{"fixture_tool":"first"}}', encoding="utf-8")
        self.catalog["checks"]["doc-integrity"]["environment_argv"] = [sys.executable, "-c", "import pathlib,sys; print(pathlib.Path(sys.argv[1]).read_text())", str(environment_file)]
        self.save_catalog()
        self.render_indices()
        code, output = self.command("run", "--task", "fixture")
        self.assertEqual(code, 0, output)
        self.assertIn("checks", output["environment"], "当前报告漏掉了实际外部工具环境")
        self.assertEqual(output["environment"]["checks"]["doc-integrity"], {"fixture_tool": "first"})
        receipt = self.root.parent / (self.root.name + "-external-receipt.json")
        self.addCleanup(lambda: receipt.unlink(missing_ok=True))
        receipt.write_text(json.dumps(output), encoding="utf-8")
        environment_file.write_text('{"ok":true,"environment":{"fixture_tool":"second"}}', encoding="utf-8")
        self.expect_failure("CP_STALE_ENVIRONMENT", "verify-receipt", "--task", "fixture", "--receipt", str(receipt))

    def test_required_tool_environment_cannot_be_missing_or_empty(self):
        for command in ([sys.executable, "-c", "raise SystemExit(3)"],
                        [sys.executable, "-c", 'print(\'{"ok":true,"environment":{}}\')']):
            with self.subTest(command=command):
                self.catalog["checks"]["doc-integrity"]["environment_argv"] = command
                self.save_catalog()
                self.render_indices()
                self.expect_failure("CP_ENVIRONMENT_INPUT", "run", "--task", "fixture")

    def test_environment_probe_cannot_be_attached_to_unimplemented_or_human_check(self):
        self.catalog["checks"]["database-scenario"]["environment_argv"] = [sys.executable, "--version"]
        self.save_catalog()
        self.expect_failure("CP_SCHEMA", "check")

    def test_external_tool_change_during_actual_check_invalidates_run(self):
        environment_file = self.root.parent / (self.root.name + "-changing-tool.json")
        self.addCleanup(lambda: environment_file.unlink(missing_ok=True))
        environment_file.write_text('{"ok":true,"environment":{"fixture_tool":"first"}}', encoding="utf-8")
        self.catalog["checks"]["external-tool-check"] = {
            "kind": "automatic", "implemented": True,
            "argv": [sys.executable, "-c", 'import pathlib,sys; pathlib.Path(sys.argv[1]).write_text(\'{"ok":true,"environment":{"fixture_tool":"second"}}\')', str(environment_file)],
            "environment_argv": [sys.executable, "-c", "import pathlib,sys; print(pathlib.Path(sys.argv[1]).read_text())", str(environment_file)]}
        self.task["verification"].append({"check": "external-tool-check", "criterion": "外部工具在运行期间不能变化"})
        self.save_catalog()
        self.save_task()
        self.render_indices()
        self.expect_failure("CP_ENVIRONMENT_CHANGED", "run", "--task", "fixture")

    def test_replaced_rules_leave_default_index_but_remain_queryable_in_archive(self):
        self.catalog["categories"]["tasks"]["archive"] = "docs/tasks/archive.md"
        self.catalog["documents"]["OLD-RULES"] = {"path": "docs/old-rules.md", "category": "standards",
                                                    "kind": "rules", "replaced_by": "RULES"}
        self.write("docs/old-rules.md", "# 旧规范\n\n## 旧正文\n\n仅作历史输入。\n")
        self.save_catalog()
        code, output = self.command("index")
        self.assertEqual(code, 0, output)
        self.assertNotIn("[OLD-RULES]", output["files"]["docs/standards-index.md"])
        self.assertIn("../old-rules.md", output["files"]["docs/tasks/archive.md"])

    def test_changed_file_reasons_are_not_copied_into_every_rule(self):
        for number in range(10):
            self.write(f"docs/change-{number}.md", "# 实际变更\n")
        code, output = self.command("route", "--task", "fixture")
        self.assertEqual(code, 0, output)
        self.assertIn("scope_reasons", output)
        self.assertTrue(any("docs/change-9.md" in reason for reason in output["scope_reasons"]["documentation"]))
        self.assertFalse(any("docs/change-" in reason for item in output["rules"] for reason in item["reasons"]))


class CurrentCatalogJourneys(unittest.TestCase):
    """使用仓库真实配置与正文的临时副本；只证明路由，不执行产品场景。"""

    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="domain-forge-current-catalog-")
        self.addCleanup(temp.cleanup)
        self.root = pathlib.Path(temp.name)
        origin = ENTRY.parents[2]
        catalog = json.loads((origin / "tools/control_plane/catalog.json").read_text(encoding="utf-8"))
        paths = {item["path"] for item in catalog["documents"].values()}
        paths.update(catalog.get("reference_inputs", {}))
        paths |= {"tools/control_plane/catalog.json", "tools/control_plane/control_plane.py", "tools/control_plane/test_control_plane.py"}
        paths |= {"tools/control_plane/delivery_evidence.py", "tools/control_plane/test_delivery_evidence.py"}
        for relative in paths:
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text((origin / relative).read_text(encoding="utf-8"), encoding="utf-8")
        # 校准场景显式复用控制面任务，不从活动指针继承下一单元的验证计划。
        self.task_path = self.root / catalog["documents"][catalog["tasks"]["control-plane-verification"]]["path"]
        self.prose = self.task_path.read_text(encoding="utf-8")
        block = re.search(r"^```control-plane-task\s*\n(.*?)^```\s*$", self.prose, re.M | re.S)
        self.task = json.loads(block.group(1))
        for args in [("init", "--quiet"), ("add", "."), ("-c", "user.name=Current Catalog Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture")]:
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)
        self.task["base"] = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.root, check=True, capture_output=True, text=True).stdout.strip()

    def route(self):
        block = "```control-plane-task\n" + json.dumps(self.task, ensure_ascii=False, indent=2) + "\n```"
        self.task_path.write_text(re.sub(r"^```control-plane-task\s*\n.*?^```\s*$", lambda _: block + "\n", self.prose, flags=re.M | re.S), encoding="utf-8")
        process = subprocess.run([sys.executable, "-B", str(ENTRY), "--root", str(self.root), "route", "--task", self.task["id"]],
                                 capture_output=True, text=True, timeout=60)
        return process.returncode, json.loads(process.stdout)

    def test_real_catalog_cannot_remove_calibration_policy_to_skip_ci(self):
        path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(path.read_text(encoding="utf-8"))
        missing = json.loads(json.dumps(baseline))
        missing.pop("maintenance")
        partial = json.loads(json.dumps(baseline))
        partial["maintenance"]["checks"].pop()
        for policy in (missing, partial):
            path.write_text(json.dumps(policy), encoding="utf-8")
            process = subprocess.run([sys.executable, "-B", str(ENTRY), "--root", str(self.root),
                                      "ci-plan", "--base", self.task["base"]], capture_output=True, text=True, timeout=10)
            self.assertNotEqual(process.returncode, 0)
            self.assertIn("CP_MAINTENANCE_POLICY", [item["code"] for item in json.loads(process.stdout)["diagnostics"]])

    def test_design_registration_is_routine_but_rule_mapping_change_requires_calibration(self):
        path = self.root / "tools/control_plane/catalog.json"
        catalog = json.loads(path.read_text(encoding="utf-8"))
        catalog["documents"]["FEATURE"] = {"path": "docs/design/feature.md", "category": "design", "kind": "design"}
        target = self.root / "docs/design/feature.md"
        target.write_text("# 业务需求设计\n\n## 合同\n\n当前用例合同，不新增通用规则。\n", encoding="utf-8")

        def invoke():
            path.write_text(json.dumps(catalog, ensure_ascii=False), encoding="utf-8")
            process = subprocess.run([sys.executable, "-B", str(ENTRY), "--root", str(self.root),
                                      "ci-plan", "--base", self.task["base"]], capture_output=True, text=True, timeout=10)
            self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
            return json.loads(process.stdout)

        routine = invoke()
        self.assertFalse(routine["maintenance_required"], "普通设计登记不应触发全部 Java 工具校准")
        self.assertFalse(routine["product_required"], "普通设计登记不应触发真实产品构建")
        catalog["routes"][0]["checks"].append("architecture-gate")
        self.assertTrue(invoke()["maintenance_required"], "真实规则映射变化必须校准")
        self.assertTrue(invoke()["product_required"], "共同规则映射变化须验证真实产品")
        catalog["routes"][0]["checks"].pop()
        catalog["documents"]["FEATURE"]["kind"] = "rules"
        self.assertTrue(invoke()["maintenance_required"], "新增规则身份不能伪装为普通设计登记")
        for args in [("add", "tools/control_plane/catalog.json", "docs/design/feature.md"),
                     ("-c", "user.name=Current Catalog Fixture", "-c", "user.email=fixture@example.invalid",
                      "commit", "--quiet", "-m", "register fixture rule")]:
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)
        self.task["base"] = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.root,
                                            check=True, capture_output=True, text=True).stdout.strip()
        target.write_text(target.read_text(encoding="utf-8") + "\n修改有效规则。\n", encoding="utf-8")
        self.assertTrue(invoke()["maintenance_required"], "规则正文变更按登记身份触发，不只依赖所在目录")
        self.assertTrue(invoke()["product_required"], "规则正文语义变化必须触发跨区真实产品验证")

    def test_partition_inputs_and_unknown_paths_are_not_document_only_green(self):
        from control_plane import ControlPlane, Rejected
        plane = ControlPlane(self.root)
        for path in ("code/pom.xml", "code/mvnw", "code/.mvn/wrapper/maven-wrapper.properties",
                     "code/verification/boot-smoke/pom.xml",
                     "code/verification/boot-smoke/src/main/java/Probe.java",
                     "code/verification/boot-smoke/src/main/resources/application.properties",
                     "code/verification/boot-smoke/src/test/java/ProbeTest.java",
                     "docs/design/minimal-engineering.md", "docs/contracts/access.md"):
            with self.subTest(path=path):
                self.assertTrue(plane.product_changed([path]))
        self.assertFalse(plane.product_changed(["docs/tasks/minimal-engineering.md", "README.md"]))
        for path in ("code/unknown/input.txt", "docs/hidden/Production.java"):
            with self.subTest(path=path), self.assertRaises(Rejected):
                plane.product_changed([path])

    def test_real_catalog_four_journeys_load_relevant_sections_not_all_identity_rules(self):
        journeys = [("create-project", "PRODUCT#新项目起步交付"),
                    ("business-demand", "REQUIREMENTS#需求与场景分析"),
                    ("platform-extension", "SYSTEM#组件与扩展设计"),
                    ("bugfix", "TASKS#评审发现与整改复验")]
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        for task_type, necessary in journeys:
            with self.subTest(task_type=task_type):
                self.task["type"] = task_type
                code, output = self.route()
                self.assertEqual(code, 0, output)
                refs = {item["ref"] for item in output["rules"]}
                self.assertIn(necessary, refs)
                self.assertNotIn("ACCESS#密码与凭证保护", refs)
                self.assertFalse(output["semantic_review_proven"])

    def test_four_task_paths_recover_rework_and_prepare_handoff_without_history(self):
        """只走控制面路径，真实产品结果及人工单元验收明确留待相应阶段。"""
        origin = ENTRY.parents[2]
        for directory in ("tools/engineering_gate", ".github/workflows"):
            for path in (origin / directory).rglob("*"):
                if path.is_file() and path.suffix in (".py", ".java", ".json", ".xml", ".yml"):
                    target = self.root / path.relative_to(origin)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(path.read_bytes())
        for args in [("add", "."), ("-c", "user.name=Current Catalog Fixture", "-c", "user.email=fixture@example.invalid",
                                    "commit", "--quiet", "-m", "current linked tool fixture")]:
            subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)
        self.task["base"] = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.root,
                                            check=True, capture_output=True, text=True).stdout.strip()
        catalog = json.loads((self.root / "tools/control_plane/catalog.json").read_text(encoding="utf-8"))
        definition = catalog["checks"]["control-plane-journeys"]
        self.assertEqual(definition["kind"], "automatic")
        self.assertTrue(definition["implemented"])
        self.assertEqual(definition["argv"], ["@python", "-B", "-m", "unittest",
            "tools.control_plane.test_control_plane.CurrentCatalogJourneys.test_four_task_paths_recover_rework_and_prepare_handoff_without_history"])

        def command(*args):
            process = subprocess.run([sys.executable, "-B", str(ENTRY), "--root", str(self.root), *args],
                                     capture_output=True, text=True, timeout=30)
            return process.returncode, json.loads(process.stdout)

        # 四类路径模拟新的交付单元，不继承仓库当前单元的真实验收与收尾。
        self.task.update(scopes=[], design_refs=[], extra_refs=[], acceptance=None, closing=None,
                         verification=[{"check": "doc-integrity", "criterion": "当前任务入口一致"}])
        goals = self.task["goals"][:]
        for task_type, selected in (("create-project", "PRODUCT#新项目起步交付"),
                                    ("business-demand", "REQUIREMENTS#需求与场景分析"),
                                    ("platform-extension", "SYSTEM#组件与扩展设计"),
                                    ("bugfix", "TASKS#评审发现与整改复验")):
            with self.subTest(task_type=task_type):
                self.task["type"] = task_type
                code, plan = self.route()
                self.assertEqual(code, 0, plan)
                code, generated = command("index", "--write")
                self.assertEqual(code, 0, generated)
                code, manifest = command("context", "--task", self.task["id"])
                self.assertEqual(code, 0, manifest)
                self.assertEqual(manifest["sections"], [])
                self.assertNotIn("ACCESS#密码与凭证保护", manifest["remaining_refs"])
                code, section = command("context", "--task", self.task["id"], "--ref", selected)
                self.assertEqual(code, 0, section)
                self.assertEqual(section["loaded_refs"], [selected])
                self.assertEqual(len(section["sections"]), 1)
                self.assertEqual(section["required_checks"], manifest["required_checks"], "部分阅读不能减少门禁")
                self.assertNotIn("CONTROL-TASK#安全检查范围收缩", section["remaining_refs"], "普通任务不能自动加载工具历史")
                self.assertTrue(all(item["executed"] is False for item in section["deferred_checks"]))

                # 新会话只需当前摘要，不能使用旧清单掩盖目标或任务事实变化。
                self.task["goals"] = []
                code, rejected = self.route()
                self.assertNotEqual(code, 0, rejected)
                self.assertIn("CP_GOAL_MISSING", [item["code"] for item in rejected["diagnostics"]])
                self.task["goals"] = goals[:]
                self.task["frontier"] = task_type + "：整改后准备完整适用验证；未验收"
                code, recovered = self.route()
                self.assertEqual(code, 0, recovered)
                code, generated = command("index", "--write")
                self.assertEqual(code, 0, generated)
                code, resumed = command("context", "--task", self.task["id"])
                self.assertEqual(code, 0, resumed)
                self.assertNotEqual(resumed["input"], manifest["input"])
                self.assertEqual(resumed["task_brief"]["frontier"], self.task["frontier"])
                self.assertEqual(resumed["sections"], [])

                # 对应违规失败，恢复原引用后通过，不删除校验或必要规则。
                valid = self.task_path.read_text(encoding="utf-8")
                self.task_path.write_text(valid + "\n[无效引用](../../README.md#不存在的小节)\n", encoding="utf-8")
                code, rejected = command("check")
                self.assertNotEqual(code, 0, rejected)
                self.assertIn("CP_LINK_ANCHOR", [item["code"] for item in rejected["diagnostics"]])
                self.task_path.write_text(valid, encoding="utf-8")
                code, checked = command("check")
                self.assertEqual(code, 0, checked)
                self.assertIsNone(self.task["acceptance"])
                self.assertIsNone(self.task["closing"])
                self.assertTrue(resumed["required_checks"], "交付准备必须保留尚需执行的责任")
                self.assertFalse(resumed["semantic_review_proven"])

    def test_real_catalog_stage_split_keeps_unfinished_mechanisms_required(self):
        self.task["scopes"] = ["architecture", "java-types", "dependency-risk", "build-gate", "tests"]
        self.task["extra_refs"] = []
        code, plan = self.route()
        self.assertEqual(code, 0, plan)
        self.assertEqual(plan.get("verification_phase"), "control-plane")
        for check in ("source-gate", "architecture-gate", "semantic-review", "build-mechanism",
                      "dependency-risk-mechanism", "control-plane-journeys"):
            self.assertIn(check, plan["required_checks"])
        self.assertEqual({item["id"] for item in plan["deferred_checks"]},
                         {"build-gate", "dependency-risk-gate", "behavior-scenarios", "runtime-scenarios"})
        self.assertTrue(all(item["status"] == "not_executed" and not item["executed"] for item in plan["deferred_checks"]))
        self.task["scopes"] = ["build"]
        code, build_only = self.route()
        self.assertEqual(code, 0, build_only)
        declarations = {"dependency-declaration-tests", "dependency-declaration-source-tests", "dependency-declaration-input-tests"}
        self.assertTrue(declarations.issubset(build_only["required_checks"]), "纯构建责任不能遗漏依赖声明机制")
        roles = {"role-boundary-tests", "role-boundary-usage-tests", "role-boundary-ownership-tests", "role-boundary-input-tests"}
        self.assertTrue(roles.issubset(build_only["required_checks"]), "纯构建责任不能遗漏已确认职责的校准机制")
        deferred = next(item for item in build_only["deferred_checks"] if item["id"] == "build-gate")
        self.assertTrue(declarations.issubset(deferred["mechanism_checks"]))
        self.assertTrue(roles.issubset(deferred["mechanism_checks"]))
        self.assertFalse(deferred["executed"])

    def test_product_java_moved_under_test_path_is_not_silently_treated_as_documentation(self):
        target = self.root / "sample/src/test/java/ProductionImplementation.java"
        target.parent.mkdir(parents=True)
        target.write_text("class ProductionImplementation {}\n", encoding="utf-8")
        code, output = self.route()
        self.assertNotEqual(code, 0)
        self.assertIn("CP_UNKNOWN_FILE", [item["code"] for item in output["diagnostics"]])

    def test_maven_advisory_inputs_route_risk_rules_not_java_declaration_rules(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        for name in ("maven-dependency-check.xml", "test_maven_advisory.py", "calibrate_advisory.py", "test_advisory_runtime.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                code, output = self.route()
                self.assertEqual(code, 0, output)
                self.assertIn("dependency-risk", output["scopes"])
                self.assertNotIn("java-types", output["scopes"])
                self.assertIn("BUILD#已确认的依赖漏洞门禁原则",
                              {item["ref"] for item in output["rules"]})
                self.assertIn("ENGINEERING-DESIGN#基础-maven-依赖安全告警",
                              {item["ref"] for item in output["rules"]})
                self.assertIn("maven-advisory-configuration-tests", output["required_checks"])
                self.assertIn("dependency-risk-mechanism", output["required_checks"])
                target.unlink()

    def test_advisory_ci_guard_rejects_budget_cache_command_and_dependency_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate/test_advisory_runtime.py"
        target = self.root / "tools/engineering_gate/test_advisory_runtime.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(origin.read_bytes())
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow_path = self.root / ".github/workflows/control-plane.yml"
        workflow_path.parent.mkdir(parents=True)
        workflow = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")

        def invoke(catalog, ci=workflow, diagnostic=None):
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            workflow_path.write_text(ci, encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "AdvisoryWorkflowChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(baseline)
        for field, diagnostic in (("argv", "基础扫描命令偏离"), ("environment_argv", "基础扫描环境入口偏离")):
            changed = json.loads(json.dumps(baseline))
            changed["checks"]["dependency-risk-mechanism"][field][-1] = "wrong-entry"
            invoke(changed, diagnostic=diagnostic)
        for original, replacement, diagnostic in (
            (' || { tail -n 200 "$DOMAIN_FORGE_GATE_ADVISORY_DATA/preparation.log" >&2; exit 1; }',
             '', "原生准备失败日志诊断丢失"),
            ("show-download-progress: true", "show-download-progress: false", "setup-java 不得注入隐式 Maven 参数"),
            ("    outputs:\n      cache-key:", "    env:\n      INVALID_RUNNER_PATH: ${{ runner.temp }}\n    outputs:\n      cache-key:",
             "job 级环境不能引用 runner 上下文"),
            ('echo "DOMAIN_FORGE_GATE_TOOLS=$RUNNER_TEMP/', 'echo "DOMAIN_FORGE_GATE_TOOLS=literal-runner-temp/',
             "runner 目录没有正确展开"),
            ("if: needs.scope-checks.outputs.maintenance-required == 'true'", "if: false", "校准条件必须来自本次差异"),
            ("needs: scope-checks", "needs: unrelated", "校准选择缺少前置核对"),
            ('ci-plan --base "$TASK_EVENT_BASE"', 'route --task control-plane-verification', "CI 不得复用长期任务历史范围"),
            ("timeout-minutes: 50", "timeout-minutes: 0", "执行须有唯一正整数预算"),
            ("timeout-minutes: 20", "timeout-minutes: 0", "执行须有唯一正整数预算"),
            ("timeout-minutes: 20", "timeout-minutes: 20\n    timeout-minutes: 30", "执行须有唯一正整数预算"),
            ("needs: advisory-data", "needs: other-job", "资料准备与校准缺少依赖"),
            ("fail-on-cache-miss: true", "fail-on-cache-miss: false", "资料缓存缺失不能放行"),
            ("fail-on-cache-miss: true", "fail-on-cache-miss: true\n          fail-on-cache-miss: false", "资料缓存缺失不能放行"),
            ("if: success()", "if: always()", "准备失败不能写入缓存"),
            ("policies, passwords, migration", "policies, passwords", "固定并行组缺失或偏离"),
            ("fail-fast: false", "fail-fast: true", "一个分组失败不能取消其他必要校准"),
            ("    needs: advisory-data", "    needs: advisory-data\n    if: false", "常规校准不能另加跳过条件"),
            ("if: matrix.group == 'source'", "if: matrix.group == 'build'", "校准入口错组或被跳过"),
            ("        if: matrix.group == 'source'\n", "", "校准入口错组或被跳过"),
            ("run: python -B tools/engineering_gate/test_source_probe.py SourceMetricBehaviorSuite", "run: true", "并行校准入口遗漏或未登记"),
            ("run: python -B tools/engineering_gate/test_source_probe.py SourceMetricBehaviorSuite",
             "run: python -B tools/engineering_gate/test_source_probe.py SourceProbeCommands SourcePartitionChecks", "并行校准入口重复"),
            ("run: python -B tools/engineering_gate/calibrate_source.py prepare",
             "if: matrix.group == 'source'\n        run: python -B tools/engineering_gate/calibrate_source.py prepare", "资料准备不能因分组跳过"),
            ("python -B tools/engineering_gate/calibrate_http.py --prepare", "python -B tools/engineering_gate/calibrate_http.py --environment",
             "每组必须保留唯一资料准备"),
            ("python -B tools/engineering_gate/calibrate_access.py --prepare", "python -B tools/engineering_gate/calibrate_access.py --environment",
             "每组必须保留唯一资料准备"),
            ("python -B tools/engineering_gate/calibrate_password.py --prepare", "python -B tools/engineering_gate/calibrate_password.py --environment",
             "每组必须保留唯一资料准备"),
            ("python -B tools/engineering_gate/calibrate_flyway.py --prepare", "python -B tools/engineering_gate/calibrate_flyway.py --environment",
             "迁移依赖与镜像须唯一准备"),
            ("if: matrix.group == 'migration' || matrix.group == 'persistence-routing' || matrix.group == 'persistence-state'",
             "if: matrix.group == 'migration'", "迁移准备须覆盖迁移及两个持久化组"),
            ('python -B tools/engineering_gate/calibrate_flyway.py --prepare "$TASK_MIGRATION_ROOT"',
             'python -B tools/engineering_gate/calibrate_flyway.py --prepare "$TASK_MIGRATION_ROOT"\n          python -B tools/engineering_gate/calibrate_flyway.py --prepare "$TASK_MIGRATION_ROOT"',
             "迁移依赖与镜像须唯一准备"),
            ("python -B tools/engineering_gate/calibrate_runtime.py --prepare", "python -B tools/engineering_gate/calibrate_runtime.py --environment", "运行依赖须唯一准备"),
            ("python -B tools/engineering_gate/calibrate_persistence.py --prepare", "python -B tools/engineering_gate/calibrate_persistence.py --environment",
             "持久化依赖须唯一准备"),
            ("if: matrix.group == 'persistence-routing' || matrix.group == 'persistence-state'",
             "if: matrix.group == 'persistence-routing'", "持久化准备须覆盖两个固定组"),
            ('python -B tools/engineering_gate/calibrate_persistence.py --prepare "$TASK_PERSISTENCE_ROOT"',
             'python -B tools/engineering_gate/calibrate_persistence.py --prepare "$TASK_PERSISTENCE_ROOT"\n          python -B tools/engineering_gate/calibrate_persistence.py --prepare "$TASK_PERSISTENCE_ROOT"',
             "持久化依赖须唯一准备"),
        ):
            self.assertIn(original, workflow)
            invoke(baseline, workflow.replace(original, replacement), diagnostic)
        invoke(baseline)

    def test_wrapper_inputs_require_bootstrap_check_and_keep_product_results_unexecuted(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        for name in ("calibrate_wrapper.py", "test_maven_wrapper.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, plan = self.route()
                    self.assertEqual(code, 0, plan)
                    self.assertIn("build-gate", plan["scopes"])
                    self.assertIn("maven-wrapper-tests", plan["required_checks"])
                    self.assertIn("build-mechanism", plan["required_checks"])
                    self.assertIn("ENGINEERING-DESIGN#官方-wrapper-启动机制校准", {item["ref"] for item in plan["rules"]})
                    self.assertIn("build-gate", [item["id"] for item in plan["deferred_checks"]])
                finally:
                    target.unlink()

    def test_lifecycle_inputs_require_all_fixed_groups_without_promoting_product_result(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        groups = {"build-mechanism", "build-test-coverage-tests", "build-artifact-tests", "build-version-tests", "build-resolution-tests", "build-input-tests"}
        for name in ("calibrate_lifecycle.py", "test_maven_lifecycle.py", "maven-lifecycle.lock.json"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, plan = self.route()
                    self.assertEqual(code, 0, plan)
                    self.assertTrue(groups.issubset(plan["required_checks"]), plan)
                    self.assertIn("BUILD#构建与依赖管理", {item["ref"] for item in plan["rules"]})
                    self.assertIn("ENGINEERING-DESIGN#真实-wrapper-构建生命周期校准", {item["ref"] for item in plan["rules"]})
                    deferred = next(item for item in plan["deferred_checks"] if item["id"] == "build-gate")
                    self.assertFalse(deferred["executed"])
                    self.assertTrue(groups.issubset(deferred["mechanism_checks"]))
                finally:
                    target.unlink()

    def test_lifecycle_partition_guard_rejects_missing_wrong_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_maven_lifecycle.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        (target.parent / "test_maven_scope.py").write_bytes((origin / "test_maven_scope.py").read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow_path = self.root / ".github/workflows/control-plane.yml"
        workflow_path.parent.mkdir(parents=True)
        workflow = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")

        def invoke(text, catalog, diagnostic=None, ci=workflow):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            workflow_path.write_text(ci, encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "MavenLifecyclePartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["build-artifact-tests"]
        invoke(original, missing, "生命周期分组缺少登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["build-input-tests"]["argv"][-1] = "MavenLifecycleVersionCommands"
        invoke(original, wrong, "生命周期固定分组命令偏离")
        wrong_environment = json.loads(json.dumps(baseline))
        wrong_environment["checks"]["build-input-tests"]["environment_argv"][2] = "tools/engineering_gate/calibrate_maven.py"
        invoke(original, wrong_environment, "生命周期环境检查遗漏")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedLifecycleCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "固定分组不能漏掉新用例")
        alias = "MavenLifecycleInputCommands = MavenLifecycleVersionCommands\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "用例不能跨组重复")
        repeated = "_single_group = MavenLifecycleInputCommands\nMavenLifecycleInputCommands = lambda: unittest.TestSuite([_single_group(), _single_group()])\n\n"
        invoke(original.replace(marker, repeated + marker), baseline, "分组内重复不能被集合吞掉")
        invoke(original, baseline, "CI 与本地生命周期分组不一致", workflow.replace("run: python -B tools/engineering_gate/test_maven_lifecycle.py MavenLifecycleInputCommands", "run: true"))
        invoke(original, baseline)

    def test_advisory_scope_does_not_restore_retired_security_approval_suites(self):
        self.task["scopes"] = ["dependency-risk"]
        self.task["extra_refs"] = []
        code, output = self.route()
        self.assertEqual(code, 0, output)
        retired = {"vulnerability-probe-tests", "vulnerability-snapshot-tests",
                   "vulnerability-import-tests", "dependency-coverage-tests",
                   "suppression-baseline-tests", "maven-build-tests", "tool-release-tests"}
        self.assertFalse(retired.intersection(output["required_checks"]), output)
        self.assertIn("maven-advisory-configuration-tests", output["required_checks"])
        self.assertIn("dependency-risk-mechanism", output["required_checks"])
        self.assertIn("dependency-risk-gate", [item["id"] for item in output["deferred_checks"]])
        self.assertFalse(retired.intersection(item["id"] for item in output["deferred_checks"]), output)

    def test_source_partition_guard_rejects_missing_wrong_omitted_and_duplicate_suites(self):
        source = ENTRY.parents[1] / "engineering_gate/test_source_probe.py"
        target = self.root / "tools/engineering_gate/test_source_probe.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        original = source.read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "SourcePartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic is not None:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)

        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["engineering-metric-probe-tests"]
        invoke(original, missing, "源码校准固定分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["engineering-metric-probe-tests"]["argv"][-1] = "SourceProbeCommands"
        invoke(original, wrong, "固定分组命令偏离登记合同")
        omitted = "class OmittedSourceCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        marker = 'if __name__ == "__main__":'
        invoke(original.replace(marker, omitted + marker), baseline, "固定分组遗漏源码校准测试")
        alias = "SourceMetricBehaviorSuite = lambda: unittest.defaultTestLoader.loadTestsFromTestCase(SourceProbeCommands)\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "同一测试被多个固定分组重复选取")
        repeated = "_single_group = SourceMetricBehaviorSuite\nSourceMetricBehaviorSuite = lambda: unittest.TestSuite([_single_group(), _single_group()])\n\n"
        invoke(original.replace(marker, repeated + marker), baseline, "同一测试在固定分组内重复选取")
        invoke(original, baseline)

    def test_maven_source_partition_guard_rejects_missing_wrong_omitted_and_duplicate_suites(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_maven_source.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        (target.parent / "test_maven_scope.py").write_bytes((origin / "test_maven_scope.py").read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "MavenSourcePartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["maven-source-metric-tests"]
        invoke(original, missing, "绑定校准固定分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["maven-source-metric-tests"]["argv"][-1] = "MavenSourceStructureSuite"
        invoke(original, wrong, "绑定校准固定分组命令偏离登记合同")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedBindingCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "固定分组遗漏绑定校准用例")
        alias = "MavenSourceMetricSuite = MavenSourceStructureSuite\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "绑定用例被固定分组重复选取")
        repeated = "_single_group = MavenSourceMetricSuite\nMavenSourceMetricSuite = lambda: unittest.TestSuite([_single_group(), _single_group()])\n\n"
        invoke(original.replace(marker, repeated + marker), baseline, "绑定用例在固定分组内重复选取")
        invoke(original, baseline)

    def test_maven_scope_inputs_require_real_build_calibration_not_only_document_checks(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        for name in ("calibrate_maven.py", "maven-scope.lock.json", "test_maven_scope.py", "MavenScopeSpy.java"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                code, output = self.route()
                self.assertEqual(code, 0, output)
                self.assertIn("build-gate", output["scopes"])
                self.assertIn("maven-scope-tests", output["required_checks"])
                self.assertIn("ENGINEERING-DESIGN#真实-maven-编译范围校准", {item["ref"] for item in output["rules"]})
                target.unlink()

    def test_maven_source_binding_inputs_require_real_scope_and_source_rules(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        self.task["verification"] = [item for item in self.task["verification"] if item["check"] == "doc-integrity"]
        self.task["design_refs"] = ["CONTROL-DESIGN#方案比较与选择"]
        for name in ("calibrate_maven_source.py", "test_maven_source.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, output = self.route()
                    self.assertEqual(code, 0, output)
                    self.assertTrue({"build-gate", "java-types", "source-format", "complexity"}.issubset(output["scopes"]))
                    self.assertIn("maven-source-tests", output["required_checks"])
                    self.assertIn("maven-source-metric-tests", output["required_checks"])
                    self.assertIn("ENGINEERING-DESIGN#真实编译范围与源码检查绑定", {item["ref"] for item in output["rules"]})
                finally:
                    target.unlink()

    def test_maven_architecture_binding_inputs_require_module_and_actual_build_rules(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        self.task["verification"] = [item for item in self.task["verification"] if item["check"] == "doc-integrity"]
        self.task["design_refs"] = ["CONTROL-DESIGN#方案比较与选择"]
        for name in ("calibrate_maven_architecture.py", "test_maven_architecture.py", "method_contracts.py",
                     "dependency_declarations.py", "test_dependency_declarations.py",
                     "MethodInventory.java", "test_method_contracts.py", "calibrate_roles.py", "role_boundaries.py",
                     "role-boundaries.lock.json", "test_role_boundaries.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, output = self.route()
                    self.assertEqual(code, 0, output)
                    self.assertTrue({"build-gate", "architecture"}.issubset(output["scopes"]))
                    self.assertIn("maven-architecture-tests", output["required_checks"])
                    self.assertIn("maven-architecture-binding-tests", output["required_checks"])
                    self.assertIn("maven-architecture-classpath-tests", output["required_checks"])
                    self.assertTrue({"dependency-declaration-tests", "dependency-declaration-source-tests",
                                     "dependency-declaration-input-tests"}.issubset(output["required_checks"]))
                    self.assertIn("ENGINEERING-DESIGN#源码依赖声明与能力入口校准", {item["ref"] for item in output["rules"]})
                    self.assertTrue({"role-boundary-tests", "role-boundary-usage-tests", "role-boundary-ownership-tests",
                                     "role-boundary-input-tests"}.issubset(output["required_checks"]))
                    self.assertIn("ENGINEERING-DESIGN#默认职责与技术注解校准", {item["ref"] for item in output["rules"]})
                    self.assertTrue({"method-comment-tests", "method-source-binding-tests", "method-inheritance-tests"}.issubset(output["required_checks"]))
                    self.assertIn("ENGINEERING-DESIGN#实际合同方法与源码说明", {item["ref"] for item in output["rules"]})
                    refs = {item["ref"] for item in output["rules"]}
                    self.assertIn("ENGINEERING-DESIGN#真实编译与模块合同绑定", refs)
                    self.assertIn("SYSTEM#逻辑模块与依赖门禁", refs)
                    self.assertNotIn("HTTP#合同生成与验证", refs)
                    self.assertNotIn("access-gate", output["required_checks"])
                finally:
                    target.unlink()


    def test_maven_architecture_partition_guard_rejects_missing_wrong_omitted_and_duplicate_suites(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_maven_architecture.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        (target.parent / "test_maven_scope.py").write_bytes((origin / "test_maven_scope.py").read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        workflow_path = self.root / ".github/workflows/control-plane.yml"
        workflow_path.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow_path.write_text(workflow_original, encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "MavenArchitecturePartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["maven-architecture-binding-tests"]
        invoke(original, missing, "架构绑定固定分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["maven-architecture-binding-tests"]["argv"][-1] = "MavenArchitectureContractSuite"
        invoke(original, wrong, "架构绑定固定分组命令偏离登记合同")
        missing_classpath = json.loads(json.dumps(baseline))
        del missing_classpath["checks"]["maven-architecture-classpath-tests"]
        invoke(original, missing_classpath, "架构绑定固定分组未登记")
        wrong_environment = json.loads(json.dumps(baseline))
        wrong_environment["checks"]["maven-architecture-classpath-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong_environment, "架构绑定环境预检偏离固定入口")
        workflow_path.write_text(workflow_original.replace("MavenArchitectureClasspathSuite", "MissingClasspathSuite"), encoding="utf-8")
        invoke(original, baseline, "架构绑定 CI 命令必须与固定分组一致且仅执行一次")
        command = "        run: python -B tools/engineering_gate/test_maven_architecture.py MavenArchitectureClasspathSuite"
        workflow_path.write_text(workflow_original.replace(command, command.replace("run:", "# run:")), encoding="utf-8")
        invoke(original, baseline, "架构绑定 CI 命令必须与固定分组一致且仅执行一次")
        workflow_path.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedArchitectureCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "固定分组遗漏架构绑定用例")
        alias = "MavenArchitectureBindingSuite = MavenArchitectureContractSuite\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "架构绑定用例被固定分组重复选取")
        repeated = "_single_group = MavenArchitectureBindingSuite\nMavenArchitectureBindingSuite = lambda: unittest.TestSuite([_single_group(), _single_group()])\n\n"
        invoke(original.replace(marker, repeated + marker), baseline, "架构绑定用例在固定分组内重复选取")
        invoke(original, baseline)


    def test_explicit_architecture_inputs_route_both_groups_and_support_boundary(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        self.task["verification"] = [item for item in self.task["verification"] if item["check"] == "doc-integrity"]
        self.task["design_refs"] = ["CONTROL-DESIGN#最低清单与实际变更"]
        for name in ("calibrate_architecture.py", "ArchitectureInventory.java", "test_architecture_probe.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, plan = self.route()
                    self.assertEqual(code, 0, plan)
                    self.assertTrue({"architecture-probe-tests", "architecture-integrity-tests"}.issubset(plan["required_checks"]))
                    self.assertIn("ENGINEERING-DESIGN#字节码与直接合同的限定校准", {item["ref"] for item in plan["rules"]})
                    self.assertIn("architecture-gate", plan["required_checks"], "限定分组不能替代完整架构责任")
                    self.assertFalse(plan["semantic_review_proven"])
                finally:
                    target.unlink()

    def test_architecture_partition_rejects_missing_wrong_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_architecture_probe.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "ArchitecturePartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["architecture-integrity-tests"]
        invoke(original, missing, "架构固定分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["architecture-integrity-tests"]["argv"][-1] = "ArchitecturePolicySuite"
        invoke(original, wrong, "架构固定分组命令偏离登记合同")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["architecture-integrity-tests"]["environment_argv"][-1] = "comments"
        invoke(original, wrong, "架构环境预检偏离固定入口")
        command = "        run: python -B tools/engineering_gate/test_architecture_probe.py ArchitectureIntegritySuite"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "架构 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedArchitectureCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "架构固定分组遗漏用例")
        alias = "ArchitectureIntegritySuite = ArchitecturePolicySuite\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "架构跨组重复用例")
        repeated = "_single_group = ArchitectureIntegritySuite\nArchitectureIntegritySuite = lambda: unittest.TestSuite([_single_group(), _single_group()])\n\n"
        invoke(original.replace(marker, repeated + marker), baseline, "架构组内重复用例")
        invoke(original, baseline)

    def test_http_partition_rejects_missing_wrong_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_http_contract.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        (target.parent / "test_maven_scope.py").write_bytes((origin / "test_maven_scope.py").read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "HttpPartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["http-drift-tests"]
        invoke(original, missing, "HTTP 固定分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["http-input-tests"]["argv"][-1] = "HttpGenerationCommands"
        invoke(original, wrong, "HTTP 固定分组命令偏离登记合同")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["http-input-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong, "HTTP 环境预检偏离固定入口")
        command = "        run: python -B tools/engineering_gate/test_http_contract.py HttpDriftCommands"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "HTTP CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedHttpCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "HTTP 固定分组遗漏用例")
        alias = "DuplicateHttpCase = HttpRequestCommands\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "HTTP 模块加载重复用例")
        invoke(original, baseline)

    def test_access_inputs_require_calibration_without_promoting_complete_iam(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        self.task["design_refs"] = []
        self.task["verification"] = [{"check": "doc-integrity", "criterion": "只核对本场景的实际输入路由"}]
        groups = {"access-transport-tests", "access-configuration-tests", "access-coverage-tests", "access-input-tests"}
        for name in ("calibrate_access.py", "sa-access.lock.json", "SaAccessInventory.java", "test_sa_access.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, plan = self.route()
                    self.assertEqual(code, 0, plan)
                    self.assertTrue(groups.issubset(plan["required_checks"]), plan)
                    self.assertIn("ACCESS#已确认的-http-凭证传递", {item["ref"] for item in plan["rules"]})
                    self.assertIn("ENGINEERING-DESIGN#真实-sa-token-凭证校准", {item["ref"] for item in plan["rules"]})
                    self.assertIn("access-gate", plan["required_checks"], "有限凭证校准不能把完整策略门禁延期")
                    self.assertNotIn("access-gate", [item["id"] for item in plan["deferred_checks"]])
                finally:
                    target.unlink()

    def test_access_partition_rejects_missing_wrong_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_sa_access.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        for name in ("test_maven_scope.py", "test_http_contract.py"):
            (target.parent / name).write_bytes((origin / name).read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "AccessPartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["access-configuration-tests"]
        invoke(original, missing, "访问校准分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["access-input-tests"]["argv"][-1] = "AccessTransportCommands"
        invoke(original, wrong, "访问分组命令偏离登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["access-input-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong, "访问环境预检偏离固定入口")
        command = "        run: python -B tools/engineering_gate/test_sa_access.py AccessCoverageCommands"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "访问 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedAccessCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "访问分组遗漏用例")
        alias = "DuplicateAccessCase = AccessTransportCommands\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "访问模块不得重复加载用例")
        invoke(original, baseline)

    def test_native_policy_inputs_require_all_checks_without_promoting_complete_access(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        self.task["design_refs"] = []
        self.task["verification"] = [{"check": "doc-integrity", "criterion": "核对实际原生策略适配输入"}]
        groups = {"policy-behavior-tests", "policy-coverage-tests", "policy-execution-tests", "policy-input-tests"}
        for name in ("calibrate_access.py", "SaAccessInventory.java", "sa_policy_contracts.py", "test_sa_policy.py", "DomainForgeUnassignedProbe.java"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, plan = self.route()
                    self.assertEqual(code, 0, plan)
                    self.assertTrue(groups.issubset(plan["required_checks"]), plan)
                    self.assertIn("ACCESS#已确认的业务接口访问策略", {item["ref"] for item in plan["rules"]})
                    self.assertIn("ENGINEERING-DESIGN#原生访问策略校准", {item["ref"] for item in plan["rules"]})
                    self.assertIn("access-gate", plan["required_checks"])
                    self.assertNotIn("access-gate", [item["id"] for item in plan["deferred_checks"]])
                finally:
                    target.unlink()

    def test_access_state_inputs_require_all_checks_without_promoting_complete_access(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        self.task["design_refs"] = []
        self.task["verification"] = [{"check": "doc-integrity", "criterion": "核对访问具体适配输入"}]
        groups = {key for key in json.loads((self.root / "tools/control_plane/catalog.json").read_text())["checks"] if key.startswith("access-state-")} | {"access-gate"}
        for name in ("calibrate_access_state.py", "access_state_environment.py", "access_state_scenarios.py", "access-state.lock.json", "AccessStateInventory.java", "AccessStateFixture.java", "AccessStateStore.java", "AccessStateSecurity.java", "test_access_state.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, plan = self.route()
                    self.assertEqual(code, 0, plan)
                    self.assertTrue(groups.issubset(plan["required_checks"]), plan)
                    self.assertIn("ACCESS#已确认的业务接口访问策略", {item["ref"] for item in plan["rules"]})
                    self.assertIn("ENGINEERING-DESIGN#访问控制具体适配", {item["ref"] for item in plan["rules"]})
                    for ref in ("ACCESS#已确认的账号与权限变更生效原则", "ACCESS#登录失败防护验证", "ACCESS#审计内容与失败验证"):
                        self.assertIn(ref, {item["ref"] for item in plan["rules"]})
                    self.assertIn("access-gate", plan["required_checks"])
                    self.assertNotIn("access-gate", [item["id"] for item in plan["deferred_checks"]])
                finally:
                    target.unlink()

    def test_native_policy_partition_rejects_missing_wrong_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_sa_policy.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        for name in ("test_maven_scope.py", "test_http_contract.py", "test_sa_access.py"):
            (target.parent / name).write_bytes((origin / name).read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "PolicyPartitionChecks"], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["policy-coverage-tests"]
        invoke(original, missing, "策略校准分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["policy-input-tests"]["argv"][-1] = "PolicyBehaviorCommands"
        invoke(original, wrong, "策略分组命令偏离登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["policy-input-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong, "策略环境预检偏离固定入口")
        command = "        run: python -B tools/engineering_gate/test_sa_policy.py PolicyCoverageCommands"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "策略 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedPolicyCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "策略分组遗漏用例")
        alias = "DuplicatePolicyCase = PolicyBehaviorCommands\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "策略模块不得重复加载用例")
        invoke(original, baseline)

    def test_persistence_adapter_partition_rejects_missing_entries_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_persistence_adapters.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        for name in ("test_persistence.py", "test_flyway.py", "test_maven_scope.py"):
            (target.parent / name).write_bytes((origin / name).read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(source, catalog, diagnostic=None):
            target.write_text(source, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "AdapterPartitionChecks"], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["persistence-mapping-tests"]
        invoke(original, missing, "具体适配分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["persistence-batch-tests"]["argv"][-1] = "AdapterMappingCommands"
        invoke(original, wrong, "具体适配命令偏离登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["persistence-condition-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong, "具体适配环境入口偏离")
        command = "        run: python -B tools/engineering_gate/test_persistence_adapters.py AdapterCompositionCommands"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "具体适配 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedAdapterCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "具体适配分组遗漏用例")
        alias = "DuplicateAdapterCase = AdapterMappingCommands\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "具体适配模块不得重复加载用例")
        invoke(original, baseline)

    def test_access_state_partition_rejects_missing_entries_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_access_state.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        for name in ("test_flyway.py", "test_maven_scope.py"):
            (target.parent / name).write_bytes((origin / name).read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(source, catalog, diagnostic=None):
            target.write_text(source, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "AccessStatePartitionChecks"], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["access-state-scope-tests"]
        invoke(original, missing, "访问状态分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["access-state-password-tests"]["argv"][-1] = "AccessScopeCommands"
        invoke(original, wrong, "访问状态命令偏离登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["access-state-session-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong, "访问状态环境入口偏离")
        command = "        run: python -B tools/engineering_gate/test_access_state.py AccessResourceCommands"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "访问状态 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedStateCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "访问状态分组遗漏用例")
        alias = "DuplicateStateCase = AccessScopeCommands\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "访问状态模块不得重复加载用例")
        invoke(original, baseline)

    def test_migration_inputs_require_all_checks_without_promoting_complete_persistence(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        self.task["design_refs"] = []
        self.task["verification"] = [{"check": "doc-integrity", "criterion": "核对实际启动迁移适配输入"}]
        groups = {"migration-behavior-tests", "migration-rejection-tests", "migration-history-tests", "migration-integrity-tests", "migration-input-tests"}
        for name in ("calibrate_flyway.py", "FlywayInventory.java", "flyway.lock.json", "test_flyway.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, plan = self.route()
                    self.assertEqual(code, 0, plan)
                    self.assertTrue(groups.issubset(plan["required_checks"]), plan)
                    self.assertIn("PERSISTENCE#flyway-迁移要求", {item["ref"] for item in plan["rules"]})
                    self.assertIn("ENGINEERING-DESIGN#启动-flyway-迁移校准", {item["ref"] for item in plan["rules"]})
                    self.assertIn("persistence-gate", plan["required_checks"])
                    self.assertNotIn("persistence-gate", [item["id"] for item in plan["deferred_checks"]])
                finally:
                    target.unlink()

    def test_migration_partition_rejects_missing_wrong_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_flyway.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        (target.parent / "test_maven_scope.py").write_bytes((origin / "test_maven_scope.py").read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "MigrationPartitionChecks"], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["migration-history-tests"]
        invoke(original, missing, "迁移校准分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["migration-input-tests"]["argv"][-1] = "MigrationBehaviorCommands"
        invoke(original, wrong, "迁移分组命令偏离登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["migration-input-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong, "迁移环境预检偏离固定入口")
        command = "        run: python -B tools/engineering_gate/test_flyway.py MigrationHistoryCommands"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "迁移 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedMigrationCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "迁移分组遗漏用例")
        alias = "DuplicateMigrationCase = MigrationBehaviorCommands\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "迁移模块不得重复加载用例")
        invoke(original, baseline)

    def test_persistence_inputs_require_all_checks_without_promoting_complete_persistence(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        self.task["design_refs"] = []
        self.task["verification"] = [{"check": "doc-integrity", "criterion": "核对真实路由与事务校准输入"}]
        groups = {"persistence-behavior-tests", "persistence-routing-tests", "persistence-transaction-tests", "persistence-execution-tests", "persistence-context-tests",
                  "persistence-binding-tests", "persistence-rollback-tests", "persistence-policy-tests", "persistence-policy-source-tests", "persistence-integrity-tests", "persistence-observer-tests", "persistence-input-tests", "datasource-isolation-tests"}
        for name in ("calibrate_persistence.py", "PersistenceInventory.java", "ResourceRoutingFixture.java", "persistence.lock.json", "test_persistence.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, plan = self.route()
                    self.assertEqual(code, 0, plan)
                    self.assertTrue(groups.issubset(plan["required_checks"]), plan)
                    self.assertIn("PERSISTENCE#事务与多数据源", {item["ref"] for item in plan["rules"]})
                    self.assertIn("ENGINEERING-DESIGN#mybatis-plus-与动态数据源校准", {item["ref"] for item in plan["rules"]})
                    self.assertIn("persistence-gate", plan["required_checks"])
                    self.assertNotIn("persistence-gate", [item["id"] for item in plan["deferred_checks"]])
                finally:
                    target.unlink()

    def test_persistence_partition_rejects_missing_wrong_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_persistence.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        for name in ("test_flyway.py", "test_maven_scope.py"):
            (target.parent / name).write_bytes((origin / name).read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "PersistencePartitionChecks"], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["persistence-context-tests"]
        invoke(original, missing, "持久化校准分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["persistence-input-tests"]["argv"][-1] = "PersistenceBehaviorCommands"
        invoke(original, wrong, "持久化分组命令偏离登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["datasource-isolation-tests"]["environment_argv"][-2] = "tools/engineering_gate/calibrate_persistence.py"
        invoke(original, wrong, "持久化环境预检偏离固定入口")
        command = "        run: python -B tools/engineering_gate/test_persistence.py PersistenceObservationNeutralityCommands"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "持久化 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedPersistenceCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "持久化分组遗漏用例")
        alias = "DuplicatePersistenceCase = PersistenceBehaviorCommands\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "持久化模块不得重复加载用例")
        invoke(original, baseline)

    def test_password_inputs_require_calibration_without_promoting_login(self):
        self.task["scopes"] = []
        self.task["extra_refs"] = []
        self.task["design_refs"] = []
        self.task["verification"] = [{"check": "doc-integrity", "criterion": "只核对本场景的实际输入路由"}]
        groups = {"password-behavior-tests", "password-rejection-tests", "password-input-tests"}
        for name in ("calibrate_password.py", "password.lock.json", "PasswordInventory.java", "test_password.py"):
            with self.subTest(name=name):
                target = self.root / "tools/engineering_gate" / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ENTRY.parents[1] / "engineering_gate" / name).read_bytes())
                try:
                    code, plan = self.route()
                    self.assertEqual(code, 0, plan)
                    self.assertTrue(groups.issubset(plan["required_checks"]), plan)
                    self.assertIn("ACCESS#密码与凭证保护", {item["ref"] for item in plan["rules"]})
                    self.assertIn("ENGINEERING-DESIGN#真实-argon2id-密码校准", {item["ref"] for item in plan["rules"]})
                    self.assertIn("access-gate", plan["required_checks"], "隔离密码校准不能把完整访问门禁延期")
                    self.assertNotIn("access-gate", [item["id"] for item in plan["deferred_checks"]])
                finally:
                    target.unlink()

    def test_password_partition_rejects_missing_wrong_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_password.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        for name in ("test_maven_scope.py", "test_http_contract.py"):
            (target.parent / name).write_bytes((origin / name).read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "PasswordPartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["password-rejection-tests"]
        invoke(original, missing, "密码校准分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["password-input-tests"]["argv"][-1] = "PasswordBehaviorCommands"
        invoke(original, wrong, "密码分组命令偏离登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["password-input-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong, "密码环境预检偏离固定入口")
        command = "        run: python -B tools/engineering_gate/test_password.py PasswordRejectionCommands"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "密码 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedPasswordCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "密码分组遗漏用例")
        alias = "DuplicatePasswordCase = PasswordBehaviorCommands\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "密码模块不得重复加载用例")
        invoke(original, baseline)

    def test_role_partition_rejects_missing_wrong_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_role_boundaries.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        for name in ("test_maven_scope.py", "test_maven_architecture.py"):
            (target.parent / name).write_bytes((origin / name).read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "RoleBoundaryPartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["role-boundary-usage-tests"]
        invoke(original, missing, "职责固定分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["role-boundary-input-tests"]["argv"][-1] = "RoleBoundaryPolicySuite"
        invoke(original, wrong, "职责固定分组命令偏离登记合同")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["role-boundary-input-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong, "职责环境预检偏离固定入口")
        command = "        run: python -B tools/engineering_gate/test_role_boundaries.py RoleBoundaryUsageSuite"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "职责 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedRoleCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "职责固定分组遗漏用例")
        alias = "RoleBoundaryUsageSuite = RoleBoundaryPolicySuite\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "职责跨组重复用例")
        repeated = "_single_group = RoleBoundaryUsageSuite\nRoleBoundaryUsageSuite = lambda: unittest.TestSuite([_single_group(), _single_group()])\n\n"
        invoke(original.replace(marker, repeated + marker), baseline, "职责组内重复用例")
        invoke(original, baseline)


    def test_dependency_declaration_partition_rejects_missing_omitted_duplicate_and_ci_drift(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_dependency_declarations.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        for name in ("test_maven_scope.py", "test_maven_architecture.py"):
            (target.parent / name).write_bytes((origin / name).read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))
        workflow = self.root / ".github/workflows/control-plane.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        workflow_original = (ENTRY.parents[2] / ".github/workflows/control-plane.yml").read_text(encoding="utf-8")
        workflow.write_text(workflow_original, encoding="utf-8")

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "DependencyDeclarationPartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["dependency-declaration-source-tests"]
        invoke(original, missing, "依赖声明固定分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["dependency-declaration-input-tests"]["argv"][-1] = "DependencyDeclarationEntrySuite"
        invoke(original, wrong, "依赖声明固定分组命令偏离登记合同")
        wrong["checks"]["dependency-declaration-input-tests"] = baseline["checks"]["dependency-declaration-input-tests"]
        wrong["checks"]["dependency-declaration-source-tests"]["environment_argv"][-1] = "--project"
        invoke(original, wrong, "依赖声明环境预检偏离固定入口")
        command = "        run: python -B tools/engineering_gate/test_dependency_declarations.py DependencyDeclarationSourceSuite"
        for replacement in (command.replace("run:", "# run:"), command + "\n" + command):
            workflow.write_text(workflow_original.replace(command, replacement), encoding="utf-8")
            invoke(original, baseline, "依赖声明 CI 入口缺失或重复")
        workflow.write_text(workflow_original, encoding="utf-8")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedDependencyCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "依赖声明固定分组遗漏用例")
        alias = "DependencyDeclarationSourceSuite = DependencyDeclarationEntrySuite\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "依赖声明跨组重复用例")
        repeated = "_single_dependency_group = DependencyDeclarationSourceSuite\nDependencyDeclarationSourceSuite = lambda: unittest.TestSuite([_single_dependency_group(), _single_dependency_group()])\n\n"
        invoke(original.replace(marker, repeated + marker), baseline, "依赖声明组内重复用例")
        invoke(original, baseline)


    def test_method_contract_partition_guard_rejects_missing_wrong_omitted_and_duplicate_suites(self):
        origin = ENTRY.parents[1] / "engineering_gate"
        target = self.root / "tools/engineering_gate/test_method_contracts.py"
        target.parent.mkdir(parents=True, exist_ok=True)
        (target.parent / "test_maven_scope.py").write_bytes((origin / "test_maven_scope.py").read_bytes())
        original = (origin / target.name).read_text(encoding="utf-8")
        catalog_path = self.root / "tools/control_plane/catalog.json"
        baseline = json.loads(catalog_path.read_text(encoding="utf-8"))

        def invoke(text, catalog, diagnostic=None):
            target.write_text(text, encoding="utf-8")
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            result = subprocess.run([sys.executable, "-B", str(target), "MethodContractPartitionChecks"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0 if diagnostic is None else 1, result.stdout + result.stderr)
            if diagnostic:
                self.assertIn(diagnostic, result.stderr)

        invoke(original, baseline)
        missing = json.loads(json.dumps(baseline))
        del missing["checks"]["method-inheritance-tests"]
        invoke(original, missing, "方法合同固定分组未登记")
        wrong = json.loads(json.dumps(baseline))
        wrong["checks"]["method-source-binding-tests"]["argv"][-1] = "MethodCommentSuite"
        invoke(original, wrong, "方法合同固定分组命令偏离登记合同")
        marker = 'if __name__ == "__main__":'
        omitted = "class OmittedMethodCase(unittest.TestCase):\n    def test_not_registered(self):\n        self.assertTrue(True)\n\n"
        invoke(original.replace(marker, omitted + marker), baseline, "固定分组遗漏方法合同用例")
        alias = "MethodSourceBindingSuite = MethodCommentSuite\n\n"
        invoke(original.replace(marker, alias + marker), baseline, "方法合同用例被固定分组重复选取")
        repeated = "_single_group = MethodSourceBindingSuite\nMethodSourceBindingSuite = lambda: unittest.TestSuite([_single_group(), _single_group()])\n\n"
        invoke(original.replace(marker, repeated + marker), baseline, "方法合同用例在固定分组内重复选取")
        invoke(original, baseline)


if __name__ == "__main__":
    unittest.main()
