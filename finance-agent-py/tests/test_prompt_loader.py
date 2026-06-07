"""prompt_loader 单元测试 — 加载、缓存、拼装、变量替换。"""
import pytest
from pathlib import Path
from prompt_loader import PromptLoader


@pytest.fixture
def prompts_dir(tmp_path: Path) -> Path:
    """创建测试用 prompts 目录。"""
    v1 = tmp_path / "v1" / "bookkeeper"
    v1.mkdir(parents=True)
    (v1 / "system.md").write_text("你是记账专员", encoding="utf-8")
    (v1 / "tool-rules.md").write_text("规则: {{categorySystem}}", encoding="utf-8")
    (v1 / "response-format.md").write_text("中文简洁", encoding="utf-8")

    shared = tmp_path / "shared"
    shared.mkdir(parents=True)
    (shared / "category-system.md").write_text("餐饮/交通/购物", encoding="utf-8")
    (shared / "safety-rules.md").write_text("拒绝无关请求", encoding="utf-8")
    return tmp_path


class TestPromptLoader:

    def test_load_single_file(self, prompts_dir):
        loader = PromptLoader(str(prompts_dir), "v1")
        assert loader.load("bookkeeper", "system") == "你是记账专员"

    def test_load_shared_file(self, prompts_dir):
        loader = PromptLoader(str(prompts_dir), "v1")
        assert loader.load_shared("category-system") == "餐饮/交通/购物"

    def test_return_empty_for_missing_file(self, prompts_dir):
        loader = PromptLoader(str(prompts_dir), "v1")
        assert loader.load("bookkeeper", "nonexistent") == ""

    def test_assemble_and_replace_variables(self, prompts_dir):
        loader = PromptLoader(str(prompts_dir), "v1")
        result = loader.assemble("bookkeeper", {"categorySystem": "餐饮/交通/购物"})
        assert "你是记账专员" in result
        assert "规则: 餐饮/交通/购物" in result
        assert "中文简洁" in result

    def test_cache_loaded_files(self, prompts_dir):
        loader = PromptLoader(str(prompts_dir), "v1")
        loader.load("bookkeeper", "system")
        result = loader.load("bookkeeper", "system")
        assert result == "你是记账专员"

    def test_assemble_supervisor(self, prompts_dir):
        v1 = prompts_dir / "v1" / "supervisor"
        v1.mkdir(parents=True)
        (v1 / "classify.md").write_text("你是一个意图分类器", encoding="utf-8")
        loader = PromptLoader(str(prompts_dir), "v1")
        assert loader.assemble("supervisor", {}) == "你是一个意图分类器"

    def test_assemble_analyst(self, prompts_dir):
        v1 = prompts_dir / "v1" / "analyst"
        v1.mkdir(parents=True)
        (v1 / "system.md").write_text("你是财务分析师", encoding="utf-8")
        (v1 / "tool-rules.md").write_text("禁止模糊词", encoding="utf-8")
        (v1 / "response-format.md").write_text("先给数字", encoding="utf-8")
        loader = PromptLoader(str(prompts_dir), "v1")
        result = loader.assemble("analyst", {})
        assert "你是财务分析师" in result
        assert "禁止模糊词" in result
        assert "先给数字" in result

    def test_assemble_single_agent_with_all_variables(self, prompts_dir):
        v1 = prompts_dir / "v1" / "single-agent"
        v1.mkdir(parents=True)
        (v1 / "system.md").write_text(
            "你是小财 userId={{userId}}\n{{accountSummary}}\n{{safetyRules}}",
            encoding="utf-8")
        (v1 / "tool-rules.md").write_text("工具参数\n{{categorySystem}}", encoding="utf-8")
        (v1 / "response-format.md").write_text(
            "日期:{{currentDate}}\n{{contextInfo}}", encoding="utf-8")
        loader = PromptLoader(str(prompts_dir), "v1")
        result = loader.assemble("single-agent", {
            "userId": "user-1",
            "accountSummary": "总余额 ¥1000",
            "safetyRules": "拒绝无关",
            "categorySystem": "餐饮/交通",
            "currentDate": "2026-06-07",
            "contextInfo": "记忆: 3条",
        })
        assert "user-1" in result
        assert "总余额 ¥1000" in result
        assert "拒绝无关" in result
        assert "餐饮/交通" in result
        assert "2026-06-07" in result
        assert "记忆: 3条" in result

    def test_clear_cache(self, prompts_dir):
        loader = PromptLoader(str(prompts_dir), "v1")
        loader.load("bookkeeper", "system")
        loader.clear_cache()
        # 缓存清空后可再次加载
        assert loader.load("bookkeeper", "system") == "你是记账专员"
