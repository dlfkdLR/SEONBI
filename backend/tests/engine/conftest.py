import numpy as np
import pytest
from example_data import build_example
from numpy.typing import NDArray

from app.engine.normalize import Scale


@pytest.fixture
def example() -> tuple[dict[str, Scale], NDArray[np.float64]]:
    return build_example()
