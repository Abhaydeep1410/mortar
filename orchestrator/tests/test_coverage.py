from __future__ import annotations

import pytest

from mortar.engine.coverage import (
    capabilities_provided,
    coverage,
    modules_for_k,
    partition_capabilities,
)
from mortar.engine.vocab import ABLATION_ORDER_ALTERNATE, ABLATION_ORDER_PRIMARY, REGISTRY_V1

CRM_CAPS = [
    "authentication.jwt",
    "authorization.rbac",
    "audit.logging",
    "crud.pagination",
    "export.csv",
    "deployment.docker",
]


def test_orders_cover_the_whole_registry_once():
    assert set(ABLATION_ORDER_PRIMARY) == set(REGISTRY_V1)
    assert set(ABLATION_ORDER_ALTERNATE) == set(REGISTRY_V1)
    assert len(ABLATION_ORDER_PRIMARY) == len(set(ABLATION_ORDER_PRIMARY)) == 6


@pytest.mark.parametrize("k", range(0, 7))
def test_primary_order_never_skips(k):
    active, skipped = modules_for_k(k)
    assert skipped == []
    assert active == list(ABLATION_ORDER_PRIMARY[:k])


def test_primary_k2_matches_benchmark_design():
    assert modules_for_k(2)[0] == ["crud.pagination", "authentication.jwt"]


def test_alternate_order_skips_modules_with_unmet_requires():
    active, skipped = modules_for_k(4, ABLATION_ORDER_ALTERNATE)
    assert set(skipped) == {"audit.logging", "authorization.rbac"}
    assert set(active) == {"deployment.docker", "export.csv"}


def test_alternate_order_k5_has_jwt_so_nothing_skipped():
    active, skipped = modules_for_k(5, ABLATION_ORDER_ALTERNATE)
    assert skipped == []
    assert len(active) == 5


def test_k_out_of_range():
    with pytest.raises(ValueError):
        modules_for_k(7)
    with pytest.raises(ValueError):
        modules_for_k(-1)


def test_docker_module_provides_three_capabilities():
    provided = capabilities_provided(["deployment.docker"])
    assert provided == {"deployment.docker", "observability.actuator", "database.flyway"}


def test_partition_and_coverage_for_crm():
    active, _ = modules_for_k(2)
    covered, uncovered = partition_capabilities(CRM_CAPS, active)
    assert covered == ["authentication.jwt", "crud.pagination"]
    assert uncovered == ["authorization.rbac", "audit.logging", "export.csv", "deployment.docker"]
    assert coverage(CRM_CAPS, active) == pytest.approx(2 / 6)


def test_coverage_is_monotonic_and_full_at_k6():
    values = [coverage(CRM_CAPS, modules_for_k(k)[0]) for k in range(7)]
    assert values == sorted(values)
    assert values[0] == 0.0
    assert values[-1] == 1.0


def test_coverage_requires_capabilities():
    with pytest.raises(ValueError):
        coverage([], [])
