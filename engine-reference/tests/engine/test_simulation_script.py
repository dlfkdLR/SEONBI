"""notebooks/simulation.py 스모크 테스트: 경로로 불러와 아주 작은 규모로 돌려 출력 구조만 확인한다."""

import importlib.util
import math
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "notebooks" / "simulation.py"


def _load():
    spec = importlib.util.spec_from_file_location("simulation_script", SCRIPT)
    assert spec is not None and spec.loader is not None
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _finite(d):
    return math.isfinite(d["mean"]) and math.isfinite(d["se"])


def test_script_runs_both_scenarios_with_few_users():
    sim = _load()
    results = sim.run_all("both", users=2, seed=1, noise=0.1)
    assert [r["scenario"] for r in results] == ["purchase", "career"]
    for res in results:
        assert set(res["adaptive"]) == {"eig", "random"}
        for s in ("eig", "random"):
            a = res["adaptive"][s]
            assert all(_finite(a[k]) for k in ("mae", "hit", "questions"))
            assert 0.0 <= a["hit"]["mean"] <= 1.0 and 3 <= a["questions"]["mean"] <= 12
            for k in (3, 5, 7):
                assert _finite(res["fixed"][k][s]["mae"]) and _finite(res["fixed"][k][s]["hit"])
        assert set(res["paired"]) == {"mae", "mae@3", "mae@5", "mae@7"}
        assert "| eig |" in sim.format_markdown(res)


def test_script_is_deterministic_and_cli_works(capsys):
    sim = _load()
    assert sim.run_experiment("career", 1, seed=5) == sim.run_experiment("career", 1, seed=5)
    assert sim.main(["--scenario", "purchase", "--users", "1", "--seed", "3"]) == 0
    assert "| 전략 | MAE |" in capsys.readouterr().out
