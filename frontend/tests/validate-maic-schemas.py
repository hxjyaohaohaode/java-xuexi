"""Validate real downloaded archives against the pinned official OpenMAIC schemas.

Portable manifests have position-based roles; the importing runtime assigns fresh
IDs before validating persisted stages, scenes and actions. This checks that bridge.
"""
import json
from pathlib import Path
import sys

from jsonschema import Draft7Validator


def verify(records):
    directory = Path(__file__).resolve().parents[1] / "node_modules/@openmaic/dsl/dist/schema"
    validators = {
        name: Draft7Validator(json.loads((directory / f"{name}.schema.json").read_text()))
        for name in ("stage", "scene", "action")
    }
    summaries = []
    for entry in records:
        code, manifest = entry["topic"], entry["manifest"]
        stage_id = f"imported-{code}"
        ids = [f"{stage_id}-role-{i}" for i in range(len(manifest["agents"]))]

        def role(index):
            if type(index) is not int or not 0 <= index < len(ids):
                raise ValueError(f"{code} invalid roster index {index!r}")
            return ids[index]

        stage = dict(manifest["stage"])
        stage["languageDirective"] = stage.pop("language", "")
        stage.update(id=stage_id, agentIds=ids, generatedAgentConfigs=[
            {**agent, "id": ids[i]} for i, agent in enumerate(manifest["agents"])
        ])
        validators["stage"].validate(stage)
        total_actions = 0
        for index, portable in enumerate(manifest["scenes"]):
            if portable["order"] != index:
                raise ValueError(f"{code} scene order {portable['order']} at index {index}")
            scene = {**portable, "id": f"{stage_id}-{index}", "stageId": stage_id}
            if "multiAgent" in scene:
                roster = dict(scene["multiAgent"])
                roster["agentIds"] = [role(i) for i in roster.pop("agentIndices")]
                scene["multiAgent"] = roster
            actions = []
            for original in scene.get("actions", []):
                action = dict(original)
                if "agentIndex" in action:
                    action["agentId"] = role(action.pop("agentIndex"))
                validators["action"].validate(action)
                actions.append(action)
            scene["actions"] = actions
            validators["scene"].validate(scene)
            total_actions += len(actions)
        summaries.append({"concept": code, "scenes": len(manifest["scenes"]), "actions": total_actions})
    return summaries


if __name__ == "__main__":
    print(json.dumps(verify(json.load(sys.stdin))))
