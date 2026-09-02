import ast
import builtins
import json
import pathlib
import sys
import tokenize

request = json.load(sys.stdin)
root = pathlib.Path(request["root"])
paths = sorted(request["paths"])
classes = []
diagnostics = []
hierarchy_incomplete = False


def module_name(rel):
    parts = list(pathlib.PurePosixPath(rel).parts)
    stem = parts[-1][:-3]
    if stem == "__init__":
        parts = parts[:-1]
    else:
        parts[-1] = stem
    return ".".join(parts) if parts else "<root>"


def child_name(prefix, name):
    if not prefix or prefix == "<root>":
        return name
    return prefix + "." + name


def absolute_from(module, is_package, level, imported):
    if module == "<root>":
        package = ""
    else:
        package = module if is_package else module.rpartition(".")[0]
    parts = [p for p in package.split(".") if p]
    if level:
        up = level - 1
        if up > len(parts):
            return None
        if up:
            parts = parts[:-up]
    if imported:
        parts.extend(imported.split("."))
    return ".".join(parts)


def dotted(expr):
    while isinstance(expr, ast.Subscript):
        expr = expr.value
    if isinstance(expr, ast.Name):
        return expr.id
    if isinstance(expr, ast.Attribute):
        left = dotted(expr.value)
        return None if left is None else left + "." + expr.attr
    return None


def resolve_base(expr, bindings, star_imported):
    raw = dotted(expr)
    if raw is None:
        try:
            display = ast.unparse(expr)
        except Exception:
            display = "<dynamic-python-base>"
        return {
            "raw": display,
            "candidate": None,
            "builtinCandidate": None,
            "line": getattr(expr, "lineno", 1),
        }

    parts = raw.split(".")
    bound = bindings.get(parts[0])
    if bound is not None:
        kind, target = bound
        if kind != "qualified" or target is None:
            return {
                "raw": raw,
                "candidate": None,
                "builtinCandidate": None,
                "line": getattr(expr, "lineno", 1),
            }
        candidate = target + ("." + ".".join(parts[1:]) if len(parts) > 1 else "")
        return {
            "raw": raw,
            "candidate": candidate,
            "builtinCandidate": None,
            "line": getattr(expr, "lineno", 1),
        }

    if len(parts) == 1:
        value = getattr(builtins, raw, None)
        if isinstance(value, type):
            target = "builtins." + raw
            return {
                "raw": raw,
                "candidate": target,
                "builtinCandidate": target,
                "line": getattr(expr, "lineno", 1),
            }
        if star_imported:
            return {
                "raw": raw,
                "candidate": None,
                "builtinCandidate": None,
                "line": getattr(expr, "lineno", 1),
            }

    return {
        "raw": raw,
        "candidate": raw,
        "builtinCandidate": None,
        "line": getattr(expr, "lineno", 1),
    }


def assigned_names(target):
    if isinstance(target, ast.Name):
        return [target.id]
    if isinstance(target, (ast.Tuple, ast.List)):
        result = []
        for element in target.elts:
            result.extend(assigned_names(element))
        return result
    return []


def scope_local_names(body):
    names = set()

    class Visitor(ast.NodeVisitor):
        def visit_FunctionDef(self, node):
            names.add(node.name)

        def visit_AsyncFunctionDef(self, node):
            names.add(node.name)

        def visit_ClassDef(self, node):
            names.add(node.name)

        def visit_Lambda(self, node):
            return

        def visit_Name(self, node):
            if isinstance(node.ctx, (ast.Store, ast.Del)):
                names.add(node.id)

        def visit_Import(self, node):
            for alias in node.names:
                names.add(alias.asname or alias.name.split(".")[0])

        def visit_ImportFrom(self, node):
            for alias in node.names:
                if alias.name != "*":
                    names.add(alias.asname or alias.name)

    visitor = Visitor()
    for statement in body:
        visitor.visit(statement)
    return names


def function_parameter_names(node):
    result = set()
    args = node.args
    for argument in list(args.posonlyargs) + list(args.args) + list(args.kwonlyargs):
        result.add(argument.arg)
    if args.vararg is not None:
        result.add(args.vararg.arg)
    if args.kwarg is not None:
        result.add(args.kwarg.arg)
    return result


def process_branch(body, prefix, bindings, module, is_package, scope_kind, star_imported):
    branch_bindings = dict(bindings)
    process_body(body, prefix, branch_bindings, module, is_package, scope_kind, star_imported)


def process_body(body, prefix, bindings, module, is_package, scope_kind, star_imported=False):
    global hierarchy_incomplete

    for node in body:
        if isinstance(node, ast.Import):
            for alias in node.names:
                bound = alias.asname or alias.name.split(".")[0]
                target = alias.name if alias.asname else alias.name.split(".")[0]
                bindings[bound] = ("qualified", target)

        elif isinstance(node, ast.ImportFrom):
            absolute = absolute_from(module, is_package, node.level, node.module)
            for alias in node.names:
                if alias.name == "*":
                    star_imported = True
                    continue
                bound = alias.asname or alias.name
                target = None if absolute is None else (absolute + "." if absolute else "") + alias.name
                bindings[bound] = ("qualified", target) if target else ("dynamic", None)

        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            function_qualified = child_name(prefix, node.name)
            function_bindings = dict(bindings)
            for local_name in scope_local_names(node.body) | function_parameter_names(node):
                function_bindings[local_name] = ("dynamic", None)
            process_body(
                node.body,
                function_qualified + ".<locals>",
                function_bindings,
                module,
                is_package,
                "function",
                star_imported,
            )
            bindings[node.name] = ("dynamic", None)

        elif isinstance(node, ast.ClassDef):
            qualified = child_name(prefix, node.name)
            bases = [resolve_base(base, bindings, star_imported) for base in node.bases]
            if any(base["candidate"] is None for base in bases):
                hierarchy_incomplete = True
                diagnostics.append({
                    "kind": "EVIDENCE_INCOMPLETE",
                    "path": current_rel,
                    "line": getattr(node, "lineno", 0) or 0,
                    "message": "dynamic or shadowed Python base prevents complete hierarchy resolution: " + qualified,
                })
            classes.append({
                "path": current_rel,
                "moduleName": module,
                "qualifiedName": qualified,
                "line": getattr(node, "lineno", 1) or 1,
                "endLine": getattr(node, "end_lineno", None) or getattr(node, "lineno", 1) or 1,
                "bases": bases,
            })
            class_bindings = dict(bindings)
            process_body(
                node.body,
                qualified,
                class_bindings,
                module,
                is_package,
                "class",
                star_imported,
            )
            bindings[node.name] = ("dynamic", None) if node.decorator_list else ("qualified", qualified)

        elif isinstance(node, ast.Assign):
            for target in node.targets:
                for name in assigned_names(target):
                    bindings[name] = ("dynamic", None)

        elif isinstance(node, ast.AnnAssign):
            for name in assigned_names(node.target):
                bindings[name] = ("dynamic", None)

        elif isinstance(node, ast.AugAssign):
            for name in assigned_names(node.target):
                bindings[name] = ("dynamic", None)

        elif isinstance(node, (ast.For, ast.AsyncFor)):
            branch = dict(bindings)
            for name in assigned_names(node.target):
                branch[name] = ("dynamic", None)
            process_body(node.body, prefix, branch, module, is_package, scope_kind, star_imported)
            process_branch(node.orelse, prefix, bindings, module, is_package, scope_kind, star_imported)

        elif isinstance(node, ast.While):
            process_branch(node.body, prefix, bindings, module, is_package, scope_kind, star_imported)
            process_branch(node.orelse, prefix, bindings, module, is_package, scope_kind, star_imported)

        elif isinstance(node, ast.If):
            process_branch(node.body, prefix, bindings, module, is_package, scope_kind, star_imported)
            process_branch(node.orelse, prefix, bindings, module, is_package, scope_kind, star_imported)

        elif isinstance(node, (ast.With, ast.AsyncWith)):
            branch = dict(bindings)
            for item in node.items:
                if item.optional_vars is not None:
                    for name in assigned_names(item.optional_vars):
                        branch[name] = ("dynamic", None)
            process_body(node.body, prefix, branch, module, is_package, scope_kind, star_imported)

        elif isinstance(node, (ast.Try, getattr(ast, "TryStar", ast.Try))):
            process_branch(node.body, prefix, bindings, module, is_package, scope_kind, star_imported)
            for handler in node.handlers:
                branch = dict(bindings)
                if handler.name:
                    branch[handler.name] = ("dynamic", None)
                process_body(handler.body, prefix, branch, module, is_package, scope_kind, star_imported)
            process_branch(node.orelse, prefix, bindings, module, is_package, scope_kind, star_imported)
            process_branch(node.finalbody, prefix, bindings, module, is_package, scope_kind, star_imported)

        elif hasattr(ast, "Match") and isinstance(node, ast.Match):
            for case in node.cases:
                process_branch(case.body, prefix, bindings, module, is_package, scope_kind, star_imported)


for current_rel in paths:
    module = module_name(current_rel)
    is_package = pathlib.PurePosixPath(current_rel).name == "__init__.py"
    path = root / pathlib.PurePosixPath(current_rel)
    try:
        with tokenize.open(str(path)) as source:
            text = source.read()
        tree = ast.parse(text, filename=current_rel, type_comments=True)
    except (SyntaxError, UnicodeError) as failure:
        hierarchy_incomplete = True
        line = getattr(failure, "lineno", 0) or 0
        diagnostics.append({
            "kind": "PARSE_ERROR",
            "path": current_rel,
            "line": line,
            "message": str(failure)[:4096],
        })
        continue

    process_body(tree.body, module, {}, module, is_package, "module")

json.dump(
    {
        "pythonVersion": ".".join(map(str, sys.version_info[:3])),
        "hierarchyIncomplete": hierarchy_incomplete,
        "classes": classes,
        "diagnostics": diagnostics,
    },
    sys.stdout,
    sort_keys=True,
    separators=(",", ":"),
)
