from euros_server.meal_planning import context,preview
from euros_server.domain import Store

def test_context_protects_inventory_and_provenance(tmp_path):
    from euros_server.catalog import Catalog
    c=context(Catalog(Store(tmp_path/'db')))
    assert c['templates']['ribs_use_up']['use_up'] is True
    assert 'turkey_chili' in c['templates'] and c['suggested_templates'] == {}
    assert 'estimates' in c['coefficient_basis']
