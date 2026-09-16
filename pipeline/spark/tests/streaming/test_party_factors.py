from streaming.party.factors import service_item_factors


def test_maps_only_requested_service_movie_ids_through_immutable_mapping():
    factors, missing = service_item_factors(
        (100, 200, 300),
        {100: 1, 200: 2},
        {1: (0.1, 0.2), 3: (0.3, 0.4)},
    )

    assert factors == {100: (0.1, 0.2)}
    assert missing == (200, 300)


def test_does_not_assume_service_and_model_ids_are_interchangeable():
    factors, missing = service_item_factors((7,), {}, {7: (0.1,)})

    assert factors == {}
    assert missing == (7,)
