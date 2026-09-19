#!/usr/bin/env python3
"""Provision two named bots on an isolated test admin. Credentials come from environment.
This changes bot inventory/settings and optionally creates test users; never use a live DB.
"""
import argparse, html, http.cookiejar, json, os, re, time, urllib.parse, urllib.request
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--url',required=True)
p.add_argument('--slots',default='0,1,2,3')
p.add_argument('--preset',default='BALANCED',choices=['BALANCED','SUPPORT','AGGRESSIVE','PASSIVE'])
p.add_argument('--quantity',type=int,default=30)
p.add_argument('--allowed',default='0,1,2,3,5,6,10,100')
p.add_argument('--create-users',action='store_true')
p.add_argument('--snapshot-only',action='store_true')
a=p.parse_args()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def get():return opener.open(a.url+'/admin/bots',timeout=10).read().decode()
def post(path,data):return opener.open(a.url+path,urllib.parse.urlencode(data).encode(),timeout=20).read().decode()
h=post('/login',dict(username=os.environ['AUTO_ADMIN_USERNAME'],password=os.environ['AUTO_ADMIN_PASSWORD']))
csrf=re.search("name='csrf' value='([^']+)'",h)[1]
def job_rows(h):return dict(re.findall(r'<tr><td>([0-9a-f-]{36})(.*?)</tr>',h,re.S))
def command(**form):
    before=job_rows(get());post('/admin/bots',dict(csrf=csrf,reason='BC soak isolated acceptance',**form))
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        rows=job_rows(get());new=set(rows)-set(before)
        if len(new)==1:
            row=rows[next(iter(new))]
            if '<td>DONE</td>' in row:return
            if '<td>FAILED</td>' in row:raise RuntimeError(html.unescape(re.sub('<[^>]+>',' ',row)))
        time.sleep(.2)
    raise RuntimeError('Admin queue result not confirmed')
if not a.snapshot_only:
    for name in ['BcFixture','BcFixture2']:
        h=get()
        if f'<strong>{name}</strong>' not in h:command(action='create',name=name,glass=0,exp=0)
    ids=sorted(set(map(int,re.findall("name='bot_id' value='(-[0-9]+)'",get()))))
    if len(ids)!=2:raise RuntimeError('Expected exactly two fixture bots; refusing to change an unrelated population')
    for bot in ids:
        command(action='leave',bot_id=bot)
        command(action='policy',bot_id=bot,enabled='true',movement='true',items='true',preset=a.preset,
                heal=80,steps=48,think=300,budget=10000,allowed=a.allowed)
        command(action='loadout',bot_id=bot,slots=a.slots,quantity=a.quantity)
    if a.create_users:
        for role in 'AB':
            name=os.environ['AUTO_USER_'+role];password=os.environ['AUTO_PASSWORD_'+role]
            post('/admin/users',dict(csrf=csrf,username=name,character_name=name,password=password,confirm_password=password,
                reason='BC soak isolated acceptance',initial_xu=100000,initial_luong=1000))
    print('Fixture bot IDs:',','.join(map(str,ids)))
# Export readable table text only; never session cookies, form values, or CSRF.
for row in re.findall(r'<tr>(.*?)</tr>',get(),re.S):
    if '<strong>BcFixture' in row:
        before_forms=row.split('<form',1)[0]
        print(html.unescape(re.sub('<[^>]+>',' ',before_forms)))
