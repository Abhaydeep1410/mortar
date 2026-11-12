import pytest

from mortar.engine.reuse import check_reuse, count_loc, module_loc, reuse_metrics, strip_java
from mortar.registry.loader import load_registry


@pytest.fixture(scope="module")
def registry():
    return load_registry()


def mods(registry, *ids):
    return [registry[i] for i in ids]


def kinds(violations):
    return sorted((v.kind, v.module_id) for v in violations)


def test_owned_import_flagged_only_for_selected_modules(registry):
    src = "package a;\nimport com.nimbusds.jwt.JWTClaimsSet;\nclass A {}\n"
    assert kinds(check_reuse({"A.java": src}, mods(registry, "authentication.jwt"))) == [("owned-import", "authentication.jwt")]
    assert check_reuse({"A.java": src}, mods(registry, "export.csv")) == []
    assert check_reuse({"A.java": src}, []) == []


def test_violation_has_file_and_line(registry):
    src = "package a;\n\nimport org.apache.commons.csv.CSVPrinter;\n"
    [v] = check_reuse({"x/A.java": src}, mods(registry, "export.csv"))
    assert (v.path, v.line, v.detail) == ("x/A.java", 3, "org.apache.commons.csv.CSVPrinter")


def test_static_and_wildcard_imports_count(registry):
    src = "import static com.opencsv.CSVWriter.DEFAULT;\nimport com.opencsv.*;\n"
    assert len(check_reuse({"A.java": src}, mods(registry, "export.csv"))) == 2


def test_imports_in_comments_and_strings_are_ignored(registry):
    src = '// import com.opencsv.CSVWriter;\n/* import com.opencsv.X; */\nclass A { String s = "import com.opencsv.Y;"; }\n'
    assert check_reuse({"A.java": src}, mods(registry, "export.csv")) == []


@pytest.mark.parametrize("decl", ["class JwtService {}", "interface JwtService {}", "record JwtService(int a) {}",
                                  "public final class JwtService extends Base {}"])
def test_redefined_type_flagged(registry, decl):
    assert kinds(check_reuse({"J.java": decl}, mods(registry, "authentication.jwt"))) == [("redefined-type", "authentication.jwt")]


def test_same_name_is_fine_when_module_not_selected(registry):
    assert check_reuse({"J.java": "class JwtService {}"}, mods(registry, "export.csv")) == []


def test_similar_names_are_not_violations(registry):
    src = "class ContactJwtServiceHelper {}\nclass PageResponses {}\n"
    assert check_reuse({"J.java": src}, mods(registry, "authentication.jwt", "crud.pagination")) == []


def test_second_filter_chain_only_with_jwt(registry):
    src = "@Bean\nSecurityFilterChain chain(HttpSecurity http) throws Exception { return http.build(); }\n"
    assert kinds(check_reuse({"S.java": src}, mods(registry, "authentication.jwt"))) == [("second-filter-chain", "authentication.jwt")]
    assert check_reuse({"S.java": src}, mods(registry, "crud.pagination")) == []


def test_using_the_module_is_not_a_violation(registry):
    src = "import com.mortar.crud.CrudController;\nclass ContactController extends CrudController<Contact, ContactRepository> {}\n"
    assert check_reuse({"C.java": src}, mods(registry, "crud.pagination")) == []


def test_non_java_files_are_skipped(registry):
    assert check_reuse({"a.xml": "import com.opencsv.X;"}, mods(registry, "export.csv")) == []


def test_strip_java_keeps_line_numbers():
    src = "a\n/* x\ny */\nb // c\n"
    assert strip_java(src).count("\n") == src.count("\n")


def test_text_block_and_escapes():
    src = 'String a = "q\\"uote"; char c = \'"\'; String t = """\n// not a comment\n""";\nint x;\n'
    assert "not a comment" not in strip_java(src)
    assert "int x;" in strip_java(src)


@pytest.mark.parametrize(
    "path,text,loc",
    [("A.java", "// c\n\nclass A {\n  /* x */\n  int a; // t\n}\n", 3),
     ("a.xml", "<a>\n<!-- c\n d -->\n<b/>\n</a>\n", 3),
     ("a.yml", "# c\na: 1\n\n  # d\nb: 2\n", 2),
     ("Dockerfile", "FROM x\n", 0), ("a.sql", "select 1;", 0)],
)
def test_count_loc(path, text, loc):
    assert count_loc(path, text) == loc


def test_reuse_metrics(registry):
    selected = mods(registry, "crud.pagination")
    m = reuse_metrics({"A.java": "class A {\n}\n", "notes.md": "ignored"}, selected)
    assert m["generated_loc"] == 2 and m["reused_loc"] == module_loc(registry["crud.pagination"]) > 0
    assert m["reuse_rate_loc"] == pytest.approx(m["reused_loc"] / (m["reused_loc"] + 2))
    assert m["counter_version"] == "loc-1"
    assert reuse_metrics({}, [])["reuse_rate_loc"] == 0.0


def test_every_module_declares_what_it_owns(registry):
    for info in registry.values():
        assert info.owns_imports, info.id
        assert all(p.endswith(".") for p in info.owns_imports)
