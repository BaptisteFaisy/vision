"""Tests du client LLM vision (sans réseau) : ``backend/llm.py``."""

import types


def test_llm_budget(module):
    """Describe et ask envoient le plafond unique, reasoning inclus."""
    from test_units import check

    calls: list[dict] = []

    def fake_client(*_args, **_kwargs):
        outer = types.SimpleNamespace()

        def create(**kwargs):
            calls.append(kwargs)
            message = types.SimpleNamespace(content='{"description": "d", "tags": ["t"]}')
            return types.SimpleNamespace(choices=[types.SimpleNamespace(message=message)])

        outer.chat = types.SimpleNamespace(completions=types.SimpleNamespace(create=create))
        return outer

    saved, module._client = module._client, fake_client
    try:
        settings = module.LLMSettings(base_url="http://x", api_key="k", model="m")
        module.describe_image("data:image/jpeg;base64,AAA", ["car"], settings)
        module.answer_question("data:image/jpeg;base64,AAA", "q ?", None, settings)
    finally:
        module._client = saved
    check("llm : budget unique (reasoning inclus)",
          len(calls) == 2 and all(call["max_tokens"] == module.MAX_TOKENS == 3000
                                  for call in calls),
          repr([call.get("max_tokens") for call in calls]))
