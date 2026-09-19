#!/usr/bin/env python3
"""Observe two real clients; mutate only the explicitly configured test accounts via admin."""
import http.cookiejar, json, os, pathlib, re, subprocess, sys, time, urllib.parse, urllib.request, uuid
root = pathlib.Path(os.environ['AUTO_RUN_DIR'])
base = os.environ['AUTO_ADMIN_URL'].rstrip('/')
container = os.environ['AUTO_DB_CONTAINER']
ids = [int(os.environ['AUTO_ID_A']), int(os.environ['AUTO_ID_B'])]
if len(set(ids)) != 2 or min(ids) <= 0:
    raise SystemExit('Two distinct positive test account IDs required')
reason = 'autoplay-' + uuid.uuid4().hex
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def post(path, data):
    return opener.open(base + path, urllib.parse.urlencode(data).encode(), timeout=15).read().decode()
def sql(statement):
    return subprocess.check_output(['docker', 'exec', '-i', container, 'mysql', '-uroot', '-N', '-B', 'army'], input=statement.encode(), timeout=15).decode().strip()
def events(role):
    p = root / f'client-{role}.jsonl'
    result = []
    for line in p.read_text().splitlines() if p.exists() else []:
        try: result.append(json.loads(line))
        except json.JSONDecodeError: pass  # writer may be appending the last line
    return result
def state(ev):
    return next((e['detail'] for e in reversed(ev) if e['type'] == 'STATE'), '')
def wait_for(predicate, description):
    deadline = time.monotonic() + 660
    while time.monotonic() < deadline:
        ev = [events(r) for r in 'AB']
        if any(e['type'] in ('FAILED', 'STOP') for log in ev for e in log):
            raise RuntimeError('Client stopped/failed while waiting for ' + description)
        if predicate(ev): return ev
        if all((root / f'client-{r}.summary').exists() for r in 'AB'):
            raise RuntimeError('Clients finished before ' + description)
        time.sleep(.1)
    raise TimeoutError(description)
root.mkdir(parents=True, exist_ok=True)
report = {'reason': reason, 'passed': False, 'accounts': ids}
try:
    html = post('/login', {'username': os.environ['AUTO_ADMIN_USERNAME'], 'password': os.environ['AUTO_ADMIN_PASSWORD']})
    csrf = re.search("name='csrf' value='([^']+)'", html)[1]
    def action(path, **data):
        return post(path, dict(csrf=csrf, reason=reason, **data))
    initial = {uid: int(sql(f'SELECT luong FROM user_ WHERE user_id={uid};')) for uid in ids}
    wait_for(lambda logs: all(state(log) == 'PARTNER' for log in logs), 'both clients in lobby')
    lobby = reason + '-lobby'
    action('/admin/broadcast', text=lobby)
    wait_for(lambda logs: all(any(e['type'] == 'BROADCAST' and lobby in e['detail'] for e in log) for log in logs), 'lobby broadcast reception')
    wait_for(lambda logs: all(state(log) == 'PLAY' for log in logs), 'both clients in combat')
    combat = reason + '-combat'
    action('/admin/broadcast', text=combat)
    for uid in ids:
        action(f'/admin/users/{uid}/wallet', currency='luong', amount='11')
    wait_for(lambda logs: all(any(e['type'] == 'BROADCAST' and combat in e['detail'] for e in log) for log in logs), 'combat broadcast reception')
    logs = wait_for(lambda logs: all(any(e['type'] == 'RELOGIN_CONFIRMED' for e in log) for log in logs), 'fresh relogin')
    checks = []
    for uid, log in zip(ids, logs):
        wallet = next(e['detail'] for e in reversed(log) if e['type'] == 'LOGIN_CONFIRMED')
        client_luong = int(re.search(r'luong=(\d+)', wallet)[1])
        db_luong = int(sql(f'SELECT luong FROM user_ WHERE user_id={uid};'))
        tx = int(sql(f"SELECT COUNT(*) FROM wallet_transaction WHERE user_id={uid} AND reason='{reason}' AND currency='luong' AND amount=11 AND balance_after=balance_before+11;"))
        audit = int(sql(f"SELECT COUNT(*) FROM admin_audit_log WHERE target_user_id={uid} AND reason='{reason}' AND action='WALLET_LUONG';"))
        check = dict(user_id=uid, initial=initial[uid], expected=initial[uid]+11, client=client_luong, database=db_luong, transactions=tx, audits=audit)
        check['passed'] = client_luong == db_luong == initial[uid]+11 and tx == audit == 1
        checks.append(check)
    report.update(checks=checks, lobby_broadcast=lobby, combat_broadcast=combat, passed=all(c['passed'] for c in checks))
except Exception as exc:
    report['error'] = str(exc)
finally:
    (root/'admin-check.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report, indent=2))
sys.exit(0 if report['passed'] else 1)
