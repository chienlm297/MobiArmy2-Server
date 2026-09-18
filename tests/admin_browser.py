"""Optional Chrome smoke test for the disposable stack created by admin-priority.sh."""
import base64
import json
import os
import select
import shutil
import subprocess
import tempfile
import time

chrome = shutil.which('google-chrome') or shutil.which('chromium')
if not chrome:
    raise SystemExit('Chrome/Chromium is required for this optional browser test')
port = int(os.environ.get('TEST_ADMIN_PORT', '18080'))
profile = tempfile.mkdtemp(prefix='army-admin-browser-')
r_in, w_in = os.pipe()
r_out, w_out = os.pipe()
process = subprocess.Popen(['bash', '-c', f'exec 3<&{r_in} 4>&{w_out}; exec "$1" --headless --no-sandbox --disable-gpu --remote-debugging-pipe --user-data-dir="$2" about:blank', 'chrome-test', chrome, profile], pass_fds=(r_in, w_out), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
os.close(r_in)
os.close(w_out)
buffer = b''
seq = 0
session = None


def call(method, params=None, target=True):
    global seq, buffer
    seq += 1
    payload = {'id': seq, 'method': method, 'params': params or {}}
    if target and session:
        payload['sessionId'] = session
    os.write(w_in, json.dumps(payload).encode() + b'\0')
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if b'\0' not in buffer:
            ready, _, _ = select.select([r_out], [], [], 1)
            if ready:
                chunk = os.read(r_out, 1048576)
                if not chunk:
                    raise RuntimeError('Chrome exited')
                buffer += chunk
            continue
        raw, buffer = buffer.split(b'\0', 1)
        message = json.loads(raw)
        if message.get('id') == seq:
            if 'error' in message:
                raise RuntimeError(message['error'])
            return message.get('result', {})
    raise TimeoutError(method)


def js(expression):
    result = call('Runtime.evaluate', {'expression': expression, 'returnByValue': True, 'awaitPromise': True})
    if 'exceptionDetails' in result:
        raise RuntimeError(result['exceptionDetails'])
    return result['result'].get('value')


def page(path):
    call('Page.navigate', {'url': f'http://127.0.0.1:{port}' + path})
    for _ in range(100):
        if js('location.pathname + " " + document.readyState') == path.split('?')[0] + ' complete':
            return
        time.sleep(.05)
    raise TimeoutError(path)


try:
    target = call('Target.createTarget', {'url':'about:blank'}, False)['targetId']
    session = call('Target.attachToTarget', {'targetId': target, 'flatten': True}, False)['sessionId']
    call('Page.enable')
    call('Emulation.setDeviceMetricsOverride', {'width':1440, 'height':1000, 'deviceScaleFactor':1, 'mobile':False})
    page('/login')
    js("document.querySelector('[name=username]').value='admin';document.querySelector('[name=password]').value='admin123';document.querySelector('form').requestSubmit()")
    for _ in range(100):
        if js('location.pathname') == '/admin':
            break
        time.sleep(.05)
    else:
        raise AssertionError('Browser login failed')
    page('/admin?q=priorityplayer')
    uid = js("Array.from(document.querySelectorAll('a[href]')).map(a=>a.getAttribute('href')).find(h=>/^\\/admin\\/users\\/\\d+$/.test(h)).split('/').pop()")
    page('/admin/users/' + uid + '?tab=inventory')
    assert js("typeof normalizeText === 'function' && normalizeText('Đồng đội') === 'dong doi'")
    assert js("document.querySelector('.picker select').options.length > 2")
    assert js("(()=>{const i=document.querySelector('.catalog-search');i.value='THIS_ITEM_DOES_NOT_EXIST';i.dispatchEvent(new Event('input'));return document.querySelector('.picker select').options.length===1})()")
    assert js("(()=>{const i=document.querySelector('.catalog-search');i.value='';i.dispatchEvent(new Event('input'));const s=document.querySelector('.picker select');s.value='ITEM:0';s.dispatchEvent(new Event('change'));return document.querySelector('.picker-preview img').src.endsWith('/admin/icons/0.png')})()")
    time.sleep(.2)
    assert js("document.querySelector('.picker-preview img').naturalWidth > 0")
    print('PASS: browser catalog filtering, accents, preview image and CSP script loading')
    page('/admin/accounts')
    screenshot = os.environ.get('TEST_SCREENSHOT', '/tmp/army-admin-accounts.png')
    with open(screenshot, 'wb') as out:
        out.write(base64.b64decode(call('Page.captureScreenshot', {'format':'png'})['data']))
    for width in [1440, 390, 320]:
        call('Emulation.setDeviceMetricsOverride', {'width':width, 'height':900, 'deviceScaleFactor':1, 'mobile':False})
        for path in ['/admin', '/admin/accounts', '/admin/rooms?state=ALL', '/admin/broadcast', '/admin/bots', '/admin/users/' + uid + '?tab=inventory']:
            page(path)
            size = js('({viewport:innerWidth, page:document.documentElement.scrollWidth})')
            assert size['page'] <= size['viewport'], (width, path, size)
    print('PASS: 18 responsive page/viewport checks (1440, 390, 320 px)')
    print('Screenshot:', screenshot)
finally:
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
    os.close(w_in)
    os.close(r_out)
    shutil.rmtree(profile, ignore_errors=True)
