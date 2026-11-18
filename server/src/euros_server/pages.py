"""Server-rendered pages for the account site and the OAuth sign-in screen.

Plain HTML forms: no client-side framework, no inline event handlers and no third-party
assets, so the only script a browser runs here is none. Every value is escaped. The theme is
the app's: white background, black text, blue accents.
"""
from html import escape

from starlette.responses import HTMLResponse

STYLE = '''
:root { color-scheme: light }
* { box-sizing: border-box }
body { background:#fff; color:#111; font:16px/1.5 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;
       margin:0; padding:24px 16px }
main { max-width:720px; margin:0 auto }
h1 { font-size:26px; margin:0 0 4px }
h2 { font-size:19px; margin:28px 0 8px }
a { color:#1558d6 }
nav { margin:12px 0 24px; display:flex; gap:16px; flex-wrap:wrap }
nav a { text-decoration:none }
label { display:block; margin:12px 0 4px; font-size:14px; color:#444 }
input[type=text], input[type=password], input[type=date], input[type=time], select {
  width:100%; max-width:420px; padding:9px 10px; border:1px solid #bbb; border-radius:8px;
  font-size:16px; background:#fff; color:#111 }
input.small { max-width:150px }
button { background:#1558d6; color:#fff; border:0; border-radius:8px; padding:10px 16px;
         font-size:16px; cursor:pointer; margin-top:16px }
button.quiet { background:#fff; color:#1558d6; border:1px solid #1558d6 }
button.danger { background:#b3261e }
table { border-collapse:collapse; width:100%; margin-top:8px }
th, td { text-align:left; padding:6px 8px 6px 0; font-size:15px; vertical-align:bottom }
th { font-weight:600; color:#444; font-size:13px }
.notice { border-left:3px solid #1558d6; padding:8px 12px; background:#f3f7ff; margin:16px 0 }
.error { border-left:3px solid #b3261e; padding:8px 12px; background:#fdf1f0; margin:16px 0 }
.muted { color:#555; font-size:14px }
.row { display:flex; gap:12px; flex-wrap:wrap; align-items:flex-end }
form.inline { display:inline }
form.inline button { margin:0 0 0 8px; padding:4px 10px; font-size:14px }
'''


def page(title, body, user=None, status=200, headers=None):
    """One document: title, optional signed-in navigation, and the caller's escaped body."""
    nav = ''
    if user:
        nav = ('<nav><a href="/account/">Account</a><a href="/account/plan">Training plan</a>'
               '<a href="/account/apps">Connected apps</a><a href="/account/password">Password</a><a href="/account/data">Your data</a>'
               f'<span class="muted">{escape(user["display_name"])}</span></nav>')
    document = (f'<!doctype html><html lang="en"><head><meta charset="utf-8">'
                f'<meta name="viewport" content="width=device-width, initial-scale=1">'
                f'<title>{escape(title)} · Euros</title><style>{STYLE}</style></head>'
                f'<body><main><h1>{escape(title)}</h1>{nav}{body}</main></body></html>')
    return HTMLResponse(document, status_code=status,
                        headers={'Cache-Control': 'no-store', 'Referrer-Policy': 'same-origin',
                                 'X-Frame-Options': 'DENY', 'X-Content-Type-Options': 'nosniff',
                                 'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'",
                                 **(headers or {})})


def notice(text, kind='notice'):
    return f'<p class="{kind}">{escape(text)}</p>' if text else ''


def field(label, name, value='', kind='text', extra=''):
    return (f'<label for="{escape(name)}">{escape(label)}</label>'
            f'<input id="{escape(name)}" name="{escape(name)}" type="{kind}" value="{escape(str(value))}" {extra}>')


def hidden(name, value):
    return f'<input type="hidden" name="{escape(name)}" value="{escape(str(value))}">'


def select(name, options, chosen):
    items = ''.join(f'<option value="{escape(v)}"{" selected" if v == chosen else ""}>{escape(label)}</option>'
                    for v, label in options)
    return f'<select name="{escape(name)}">{items}</select>'
