"""Integration tests for a DISPOSABLE stack; run through tests/admin-priority.sh."""
import http.client
import os
import re
import subprocess
import urllib.parse

PORT = int(os.environ.get('TEST_ADMIN_PORT', '18080'))
DB = os.environ['TEST_DB_CONTAINER']
checks = 0


def check(condition, label):
    global checks
    assert condition, label
    checks += 1
    print('PASS:', label, flush=True)


def sql(query):
    return subprocess.check_output(['docker', 'exec', DB, 'mysql', '-uroot', '-N', '-B', 'army', '-e', query], text=True).strip()


class Client:
    def __init__(self):
        self.cookie = ''
        self.csrf = ''

    def request(self, path, form=None):
        conn = http.client.HTTPConnection('127.0.0.1', PORT, timeout=20)
        headers = {'Cookie': self.cookie}
        if form is not None:
            headers['Content-Type'] = 'application/x-www-form-urlencoded'
        conn.request('GET' if form is None else 'POST', path,
                     None if form is None else urllib.parse.urlencode(form), headers)
        response = conn.getresponse()
        if response.getheader('Set-Cookie'):
            self.cookie = response.getheader('Set-Cookie').split(';')[0]
        data = response.read().decode('utf-8', errors='replace')
        match = re.search("name='csrf' value='([^']+)'", data)
        if match:
            self.csrf = match[1]
        result = response.status, data, response.getheader('Location')
        conn.close()
        return result

    def post(self, path, **form):
        return self.request(path, {'csrf': self.csrf, 'reason': 'Priority integration test', **form})

    def login(self, username, password='testing123'):
        check(self.request('/login', {'username': username, 'password': password})[0] == 303, 'login ' + username)
        check(self.request('/admin')[0] == 200, 'dashboard ' + username)


owner = Client()
check(owner.request('/admin')[0] == 303, 'anonymous redirected')
check(owner.request('/login', {'username': 'admin', 'password': 'incorrect'})[0] == 401, 'wrong password rejected')
owner.login('admin', 'admin123')
check(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='army' AND table_name='admin_account'") == '1', 'admin schema present')
check(sql("SELECT LEFT(password_hash,4) FROM admin_account WHERE username='admin'") == '$2a$', 'owner password BCrypt')
for name, role in [('testadmin', 'ADMIN'), ('testmod', 'MODERATOR'), ('testview', 'VIEWER')]:
    check(owner.post('/admin/accounts', username=name, password='testing123', role=role, enabled='true')[0] == 303, 'create ' + role)
check(owner.post('/admin/accounts', username='testview', password='testing123', role='VIEWER', enabled='true')[0] == 400, 'duplicate admin rejected')
check(owner.post('/admin/accounts', id=1, role='VIEWER', enabled='true')[0] == 400, 'last OWNER cannot be demoted')
check(owner.post('/admin/accounts', id=1, role='OWNER', enabled='false')[0] == 400, 'last OWNER cannot be disabled')
check(owner.post('/admin/accounts', username='badpass', password='short', role='VIEWER', enabled='true')[0] == 400, 'short admin password rejected')
admin, mod, view = Client(), Client(), Client()
for client, name in [(admin, 'testadmin'), (mod, 'testmod'), (view, 'testview')]:
    client.login(name)
    check(client.request('/admin/accounts')[0] == 403, 'accounts restricted: ' + name)
    check(client.post('/admin/accounts', username='intruder', password='testing123', role='OWNER', enabled='true')[0] == 403, 'cannot escalate: ' + name)

status, _, location = admin.post('/admin/users', username='priorityplayer', password='player123', confirm_password='player123', character_name='Priority Player', initial_xu=1000, initial_luong=1000)
check(status == 303, 'ADMIN creates player')
uid = int(sql("SELECT id FROM user WHERE username='priorityplayer'"))
base = '/admin/users/' + str(uid)
for tab in ['overview', 'characters', 'equipment', 'inventory', 'missions', 'friends', 'history']:
    check(owner.request(base + '?tab=' + tab)[0] == 200, 'player tab ' + tab)
html = view.request(base + '?tab=characters')[1]
check("action='" + base + "/character'" not in html, 'VIEWER edit form hidden')
for action in ['wallet', 'kick', 'ban', 'unban', 'password', 'lock', 'unlock', 'restore', 'delete', 'rename', 'cup', 'character', 'inventory', 'equipment-add', 'equipment-remove']:
    check(view.post(base + '/' + action)[0] == 403, 'VIEWER denies ' + action)
for action in ['wallet', 'password', 'restore', 'delete', 'rename', 'cup', 'character', 'inventory', 'equipment-add', 'equipment-remove']:
    check(mod.post(base + '/' + action)[0] == 403, 'MODERATOR denies ' + action)
check(mod.request('/admin/users/new')[0] == 403, 'MODERATOR create page denied')
check(mod.post(base + '/lock')[0] == 303, 'MODERATOR locks')
check('priorityplayer' in owner.request('/admin?filter=LOCKED')[1], 'locked filter includes player')
check(mod.post(base + '/unlock')[0] == 303, 'MODERATOR unlocks')
check(owner.post(base + '/delete')[0] == 303, 'OWNER soft deletes')
for action in ['lock', 'unlock']:
    check(mod.post(base + '/' + action)[0] == 403, 'MODERATOR cannot bypass deleted via ' + action)
check(sql(f'SELECT status FROM user_account_state WHERE user_id={uid}') == 'DELETED', 'denied restore leaves data unchanged')
check('priorityplayer' in owner.request('/admin?filter=DELETED')[1], 'deleted filter includes player')
check(owner.post(base + '/restore')[0] == 303, 'OWNER restores')
check(mod.post(base + '/ban', minutes=5)[0] == 303, 'MODERATOR bans')
check('priorityplayer' in owner.request('/admin?filter=BANNED')[1], 'banned filter includes player')
check(mod.post(base + '/unban')[0] == 303, 'MODERATOR unbans')
check('priorityplayer' not in re.sub(r"<section class='panel'><div class='panel-head'><div><p class='eyebrow'>SECURITY.*", '', owner.request('/admin?filter=ONLINE')[1], flags=re.S), 'offline player excluded from online list')
check(owner.request('/admin?filter=INVALID')[0] == 400, 'bad filter rejected')
check(owner.request('/admin?page=invalid')[0] == 400, 'bad page rejected')
check(owner.request('/admin?page=2147483647')[0] == 200, 'large page clamped')
form = dict(glass_id=0, exp=100, point=10, **{f'ability_{i}': i+1 for i in range(5)})
check(admin.post(base + '/character', **form)[0] == 303, 'named ability fields saved')
check(admin.post(base + '/character', **{**form, 'ability_3': -1})[0] == 400, 'negative luck rejected')
check(admin.post(base + '/character', **{**form, 'ability_3': 100001})[0] == 400, 'excessive luck rejected')
check(admin.post(base + '/character', **{k:v for k,v in form.items() if k != 'ability_0'})[0] == 400, 'missing stat rejected')
check("name='ability_3' min='0' max='100000' value='4'" in owner.request(base+'?tab=characters')[1], 'saved luck rendered')
check(admin.post(base + '/inventory', item_key='ITEM:0', amount=1)[0] == 303, 'catalog item granted')
check(admin.post(base + '/inventory', item_key='BAD:0', amount=1)[0] == 400, 'invalid catalog kind rejected')
check(admin.post(base + '/inventory', item_key='ITEM:999999', amount=1)[0] == 400, 'unknown catalog item rejected')
check(owner.request('/admin/assets/admin.js')[0] == 200, 'external JS served')
check(owner.request('/admin/icons/0.png')[0] == 200, 'item preview icon served')
for state in ['ACTIVE', 'ALL', 'PLAYING', 'WAITING', 'EMPTY']:
    check(view.request('/admin/rooms?state=' + state)[0] == 200, 'rooms view ' + state)
check(view.request('/admin/rooms?state=INVALID')[0] == 400, 'bad room filter rejected')
check('Game loop hoạt động' in owner.request('/admin/rooms')[1], 'game loop publishes room snapshot')
check('Bàn ' in owner.request('/admin/rooms?state=ALL')[1], 'room snapshot contains boards')
for client in [mod, view]:
    check(client.post('/admin/broadcast', text='hello')[0] == 403, 'broadcast role denied')
check(admin.post('/admin/broadcast', text='Thông báo kiểm thử')[0] == 303, 'broadcast accepted')
for text, reason in [('', 'test'), ('x'*301, 'test'), ('line\nbreak', 'test'), ('hello', '')]:
    check(admin.post('/admin/broadcast', text=text, reason=reason)[0] == 400, 'bad broadcast rejected')
check(admin.request('/admin/broadcast', {'text': 'No CSRF', 'reason': 'test'})[0] == 400, 'CSRF required')
check(sql("SELECT COUNT(*) FROM admin_audit_log WHERE action='BROADCAST'") == '1', 'only accepted broadcast audited')
viewid = int(sql("SELECT id FROM admin_account WHERE username='testview'"))
check(owner.post('/admin/accounts', id=viewid, role='VIEWER', enabled='false')[0] == 303, 'disable admin')
check(view.request('/admin')[0] == 303, 'disabled session revoked')
check(view.request('/login', {'username':'testview', 'password':'testing123'})[0] == 401, 'disabled login denied')
check(owner.post('/admin/accounts', id=viewid, role='VIEWER', enabled='true', password='newpassword123')[0] == 303, 'reset admin password')
check(view.request('/login', {'username':'testview', 'password':'testing123'})[0] == 401, 'old password rejected')
view.login('testview', 'newpassword123')
check(owner.post('/admin/accounts', id=viewid, role='MODERATOR', enabled='true')[0] == 303, 'change admin role')
check(view.request('/admin')[0] == 303, 'role change revokes session')
check(sql("SELECT COUNT(*) FROM admin_audit_log WHERE detail LIKE '%testing123%' OR detail LIKE '%newpassword123%'") == '0', 'audit excludes passwords')
# Exercise pagination past the old 200-row limit, with filtering before pagination.
for i in range(211):
    sql(f"INSERT INTO user (id,username,password) VALUES ({10000+i},'pagefixture{i:03d}','unused'); "
        f"INSERT INTO user_ (user_id,name,xu,luong,cup,glass) VALUES ({10000+i},'pagefixture{i:03d}',0,0,0,0)")
sql("INSERT INTO user_account_state (user_id,status) VALUES (10210,'LOCKED')")
first = owner.request('/admin?q=pagefixture&filter=OFFLINE&page=1')[1]
last = owner.request('/admin?q=pagefixture&filter=OFFLINE&page=9')[1]
check('211 người chơi · Trang 1 / 9' in first, 'pagination counts all 211 users')
check('pagefixture000' in first and 'pagefixture025' not in first, 'first page limited to 25')
check('pagefixture210' in last and 'Trang 9 / 9' in last, 'last page includes users beyond 200')
filtered = owner.request('/admin?q=pagefixture&filter=LOCKED')[1]
check('1 người chơi · Trang 1 / 1' in filtered and 'pagefixture210' in filtered, 'filter applied before pagination')
check('Trang 1 / 1' in owner.request('/admin?q=no_matching_fixture')[1], 'empty result pagination')
print(f'{checks} integration checks passed')

# Bot commands are asynchronous; inspect the command row, not only HTTP acceptance.
import time

def bot_command(client, expected='DONE', **form):
    status, _, location = client.post('/admin/bots', **form)
    check(status == 303, 'bot command accepted: ' + form['action'])
    command = re.search(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', urllib.parse.unquote(location)).group()
    for _ in range(150):
        html = owner.request('/admin/bots')[1]
        row = re.search(r'<tr><td>' + command + r'.*?</tr>', html, re.S)
        if row and ('<td>DONE</td>' in row[0] or '<td>FAILED</td>' in row[0] or '<td>DONE_AUDIT_ERROR</td>' in row[0]):
            check('<td>' + expected + '</td>' in row[0], 'bot command result: ' + form['action'] + ' -> ' + expected)
            return row[0]
        time.sleep(.1)
    raise AssertionError('Timed out waiting for bot command ' + command)

check(owner.request('/admin/bots')[0] == 200, 'bot page accessible')
check(owner.request('/admin/bots?state=INVALID')[0] == 400, 'bot invalid filter rejected')
check(owner.request('/admin/bots?page=bad')[0] == 400, 'bot invalid page rejected')
check(owner.post('/admin/bots', action='unknown')[0] == 400, 'unknown bot action rejected')
check(owner.post('/admin/bots', action='create', name='NoReasonBot', reason='')[0] == 400, 'bot reason required')
check(owner.request('/admin/bots', {'action':'create','name':'CsrfBot','reason':'test'})[0] == 400, 'bot CSRF required')
check(owner.post('/admin/bots', action='create', name='InvalidBot', glass=10)[0] == 400, 'invalid bot character rejected')
check(owner.post('/admin/bots', action='create', name='<script>evil</script>')[0] == 400, 'invalid bot name rejected')
check(mod.request('/admin/bots')[0] == 200, 'moderator can view bots')
for action in ['create', 'leave', 'remove', 'target']:
    check(mod.post('/admin/bots', action=action)[0] == 403, 'moderator cannot ' + action + ' bot')
check("action='/admin/bots'" not in re.sub(r"<form method='get'.*?</form>", '', mod.request('/admin/bots')[1]), 'readonly bot mutation forms hidden')
owner.post('/admin/accounts', username='botviewer', password='testing123', role='VIEWER', enabled='true')
botviewer = Client()
botviewer.login('botviewer')
check(botviewer.request('/admin/bots')[0] == 200 and botviewer.post('/admin/bots', action='create')[0] == 403, 'viewer bot permissions')
result = bot_command(owner, action='create', name='AdminTestBot', glass=0, exp=100)
bot_id = int(re.search(r'#(-\d+)', result).group(1))
check(bot_id < 0, 'created bot uses negative ID')
check('AdminTestBot' in owner.request('/admin/bots?q=AdminTestBot&state=IDLE')[1], 'bot search finds created idle bot')
bot_command(owner, expected='FAILED', action='create', name='admintestbot', glass=0, exp=0)
bot_command(admin, action='target', bot_id=bot_id, mode='LOW_HP')
check('LOW_HP' in owner.request('/admin/bots?q=' + str(bot_id))[1], 'bot target override visible')
bot_command(admin, action='target', bot_id=bot_id, mode='DEFAULT')
check('RANDOM (mặc định)' in owner.request('/admin/bots?q=' + str(bot_id))[1], 'bot target default restored')
bot_command(admin, action='leave', bot_id=bot_id)
bot_command(owner, action='remove', bot_id=bot_id)
check('Không tìm thấy bot phù hợp.' in owner.request('/admin/bots?q=AdminTestBot')[1], 'removed bot absent from snapshot')
bot_command(owner, expected='FAILED', action='remove', bot_id=bot_id)
check(int(sql("SELECT COUNT(*) FROM admin_audit_log WHERE action='BOT_REQUEST'")) == 5, 'bot mutations have durable intent audits')
check(int(sql("SELECT COUNT(*) FROM admin_audit_log WHERE action='BOT_RESULT'")) == 5, 'bot mutations have result audits')
print(f'{checks} integration checks including bot admin passed')
