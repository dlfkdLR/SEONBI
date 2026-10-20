"""pytest를 backend/ 어디서 실행해도 `app` 패키지를 불러올 수 있게 한다."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
