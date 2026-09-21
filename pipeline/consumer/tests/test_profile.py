import numpy as np

from feelm_consumer.profile import ProfileInput, fold_in


def test_fold_in_uses_als_wr_regularization() -> None:
    factors = {1: np.array([1.0, 0.0]), 2: np.array([0.0, 2.0])}
    result = fold_in(
        factors,
        [ProfileInput(1, 4.0, "rating"), ProfileInput(2, 2.0, "rating")],
        reg_param=0.5,
    )

    assert result is not None
    np.testing.assert_allclose(result.vector, [2.0, 0.8], rtol=0, atol=1e-12)
    assert result.factor_backed_input_count == 2


def test_fold_in_returns_none_without_factor_backed_input() -> None:
    assert fold_in({1: np.array([1.0])}, [ProfileInput(2, 5.0, "rating")], 0.5) is None
