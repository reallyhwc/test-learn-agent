"""config_loader 单元测试 — 环境变量优先级、YAML 替换、异常处理。"""
import os
from unittest.mock import patch, mock_open

import pytest


@pytest.fixture(autouse=True)
def clear_lru_cache():
    """每个测试前清除 load_config 的 LRU 缓存。"""
    from config_loader import load_config
    load_config.cache_clear()
    yield
    load_config.cache_clear()


def test_get_llm_config_env_overrides_yaml():
    """环境变量优先于 config.yaml 中的值。"""
    with patch.dict(os.environ, {
        "LLM_API_KEY": "env-key-123",
        "LLM_BASE_URL": "https://env.example.com",
        "LLM_MODEL": "env-model",
    }):
        from config_loader import get_llm_config
        config = get_llm_config()
        assert config["api_key"] == "env-key-123"
        assert config["base_url"] == "https://env.example.com"
        assert config["model"] == "env-model"


def test_get_llm_config_missing_api_key_raises():
    """api_key 为空时应抛出 ValueError。"""
    with patch.dict(os.environ, {"LLM_API_KEY": ""}, clear=False):
        # 确保 config.yaml 中也没有 api_key
        yaml_content = "llm:\n  api_key: ''\n  base_url: https://api.deepseek.com\n  model: deepseek-chat\n"
        with patch("builtins.open", mock_open(read_data=yaml_content)):
            with patch("pathlib.Path.exists", return_value=True):
                from config_loader import get_llm_config, load_config
                load_config.cache_clear()
                with pytest.raises(ValueError, match="LLM_API_KEY"):
                    get_llm_config()


def test_load_config_yaml_variable_substitution():
    """YAML 中的 ${VAR} 应被环境变量替换。"""
    yaml_content = "llm:\n  api_key: '${MY_TEST_KEY}'\nservices:\n  backend:\n    port: 8080\n"
    with patch.dict(os.environ, {"MY_TEST_KEY": "replaced-key"}):
        with patch("builtins.open", mock_open(read_data=yaml_content)):
            with patch("pathlib.Path.exists", return_value=True):
                from config_loader import load_config
                load_config.cache_clear()
                config = load_config()
                assert config["llm"]["api_key"] == "replaced-key"


def test_get_agent_config_returns_expected_fields():
    """get_agent_config 应返回 agent/mcp/mode/端口等字段。"""
    from config_loader import get_agent_config
    config = get_agent_config()
    assert "agent" in config
    assert "mcp" in config
    assert "mode" in config
    assert "agent_port" in config
    assert "mcp_port" in config
    assert isinstance(config["available_agents"], list)
    assert isinstance(config["available_mcps"], list)


def test_load_config_file_not_found():
    """config.yaml 不存在时应抛出 FileNotFoundError。"""
    with patch("pathlib.Path.exists", return_value=False):
        from config_loader import load_config
        load_config.cache_clear()
        with pytest.raises(FileNotFoundError, match="配置文件不存在"):
            load_config()
