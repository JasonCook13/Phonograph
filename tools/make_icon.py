import math, subprocess, sys
from PIL import Image, ImageDraw
BG = '#6B1A2D'; IV = '#F2ECE0'; GOLD = '#D6AC52'; GROOVE = '#DDD2BF'; EDGE = '#4A1220'
R = 26.0                      # record radius (was 22)
K = R / 22.0
PIV = (32.1, 75.9); BEND = (38.6, 55.7); TIP = (45.0, 43.0)   # ~9 degree bend

def arm(edge, mono=False):
    ang = math.degrees(math.atan2(TIP[1] - BEND[1], TIP[0] - BEND[0]))
    ux, uy = math.cos(math.radians(ang)), math.sin(math.radians(ang))
    end = (TIP[0] - 1.5 * ux, TIP[1] - 1.5 * uy)
    d = f'M{PIV[0]} {PIV[1]} L{BEND[0]} {BEND[1]} L{end[0]:.2f} {end[1]:.2f}'
    col = 'white' if mono else GOLD
    out = ''
    if edge and not mono:
        op, w = edge
        out = (f'<path d="{d}" fill="none" stroke="{EDGE}" stroke-opacity="{op}" stroke-width="{3.1 + w}" stroke-linecap="round" stroke-linejoin="round"/>'
               f'<rect x="{TIP[0]-3.3-w/2:.2f}" y="{TIP[1]-1.9-w/2:.2f}" width="{6.6+w:.2f}" height="{3.8+w:.2f}" rx="1.4" transform="rotate({ang:.1f} {TIP[0]} {TIP[1]})" fill="{EDGE}" fill-opacity="{op}"/>')
    return d, ang, out + (f'<path d="{d}" fill="none" stroke="{col}" stroke-width="3.1" stroke-linecap="round" stroke-linejoin="round"/>'
                          f'<rect x="{TIP[0]-3.3:.2f}" y="{TIP[1]-1.9:.2f}" width="6.6" height="3.8" rx="1.1" transform="rotate({ang:.1f} {TIP[0]} {TIP[1]})" fill="{col}"/>')

def svg(edge=None, bg=True, mono=False):
    d, ang, a = arm(edge, mono)
    rings = [r * K for r in (19.6, 17.0, 14.4, 11.8)]
    label = 6.2 * K
    piv = (f'<circle cx="{PIV[0]}" cy="{PIV[1]}" r="4.6" fill="{GOLD}"/><circle cx="{PIV[0]}" cy="{PIV[1]}" r="1.8" fill="{BG}"/>')
    if mono:
        g = ''.join(f'<circle cx="54" cy="54" r="{rr:.2f}" fill="none" stroke="black" stroke-width=".9"/>' for rr in rings)
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
                f'<defs><mask id="m"><rect width="108" height="108" fill="white"/><circle cx="54" cy="54" r="{label:.2f}" fill="black"/>{g}'
                f'<path d="{d}" stroke="black" stroke-width="5.6" stroke-linecap="round" stroke-linejoin="round" fill="none"/>'
                f'<rect x="{TIP[0]-4.6:.2f}" y="{TIP[1]-3.2:.2f}" width="9.2" height="6.4" rx="2" transform="rotate({ang:.1f} {TIP[0]} {TIP[1]})" fill="black"/>'
                f'<circle cx="{PIV[0]}" cy="{PIV[1]}" r="6" fill="black"/>'
                f'</mask></defs><circle cx="54" cy="54" r="{R}" fill="white" mask="url(#m)"/>' + a +
                f'<path fill="white" fill-rule="evenodd" d="M{PIV[0]-4.6} {PIV[1]}a4.6 4.6 0 1 0 9.2 0a4.6 4.6 0 1 0 -9.2 0Z M{PIV[0]-1.8} {PIV[1]}a1.8 1.8 0 1 1 3.6 0a1.8 1.8 0 1 1 -3.6 0Z"/></svg>')
    g = ''.join(f'<circle cx="54" cy="54" r="{rr:.2f}" fill="none" stroke="{GROOVE}" stroke-width=".6"/>' for rr in rings)
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
            + (f'<rect width="108" height="108" fill="{BG}"/>' if bg else '')
            + f'<circle cx="54" cy="54" r="{R}" fill="{IV}"/>{g}'
            f'<circle cx="54" cy="54" r="{label:.2f}" fill="{BG}"/>'
            f'<circle cx="54" cy="54" r="{4.7*K:.2f}" fill="none" stroke="{GOLD}" stroke-width=".6"/>'
            f'<circle cx="54" cy="54" r="1.2" fill="{IV}"/>{a}{piv}</svg>')

def render(s, size, out, viewbox=None):
    if viewbox: s = s.replace('viewBox="0 0 108 108"', f'viewBox="{viewbox}"')
    open('/tmp/_i.svg', 'w').write(s)
    subprocess.run(['rsvg-convert', '-w', str(size), '-h', str(size), '/tmp/_i.svg', '-o', out], check=True)

SUBTLE = (0.45, 0.9)
if __name__ == '__main__':
    render(svg(SUBTLE), 512, 'v5_subtle.png'); render(svg(None), 512, 'v5_none.png')
