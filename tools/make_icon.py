import math, subprocess, os, sys
from PIL import Image, ImageDraw
BG = '#6B1A2D'; IV = '#F2ECE0'; GOLD = '#D6AC52'; GROOVE = '#DDD2BF'
PIV = (32, 74.5); BEND = (36.6, 55.0); TIP = (46.6, 45.4)   # straighter arm, tip nearer the label

def parts(mono=False):
    ang = math.degrees(math.atan2(TIP[1] - BEND[1], TIP[0] - BEND[0]))
    ux, uy = math.cos(math.radians(ang)), math.sin(math.radians(ang))
    end = (TIP[0] - 1.5 * ux, TIP[1] - 1.5 * uy)
    d = f'M{PIV[0]} {PIV[1]} L{BEND[0]} {BEND[1]} L{end[0]:.2f} {end[1]:.2f}'
    col = 'white' if mono else GOLD
    outline = '' if mono else f'<path d="{d}" fill="none" stroke="#4A1220" stroke-opacity=".9" stroke-width="4.3" stroke-linecap="round" stroke-linejoin="round"/>'
    hs_out = '' if mono else f'<rect x="{TIP[0]-3.9:.2f}" y="{TIP[1]-2.5:.2f}" width="7.8" height="5.0" rx="1.6" transform="rotate({ang:.1f} {TIP[0]:.2f} {TIP[1]:.2f})" fill="#4A1220" fill-opacity=".9"/>'
    arm = f'<path d="{d}" fill="none" stroke="{col}" stroke-width="3.1" stroke-linecap="round" stroke-linejoin="round"/>'
    hs = f'<rect x="{TIP[0]-3.3:.2f}" y="{TIP[1]-1.9:.2f}" width="6.6" height="3.8" rx="1.1" transform="rotate({ang:.1f} {TIP[0]:.2f} {TIP[1]:.2f})" fill="{col}"/>'
    return d, ang, outline + hs_out + arm + hs

def svg(bg=True, mono=False):
    d, ang, armsvg = parts(mono)
    if mono:
        g = ''.join(f'<circle cx="54" cy="54" r="{rr}" fill="none" stroke="black" stroke-width=".9"/>' for rr in (19.6, 17.0, 14.4, 11.8))
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
                f'<defs><mask id="m"><rect width="108" height="108" fill="white"/><circle cx="54" cy="54" r="6.2" fill="black"/>{g}'
                f'<path d="{d}" stroke="black" stroke-width="5.6" stroke-linecap="round" stroke-linejoin="round" fill="none"/>'
                f'<rect x="{TIP[0]-4.6:.2f}" y="{TIP[1]-3.2:.2f}" width="9.2" height="6.4" rx="2" transform="rotate({ang:.1f} {TIP[0]:.2f} {TIP[1]:.2f})" fill="black"/>'
                '</mask></defs><circle cx="54" cy="54" r="22" fill="white" mask="url(#m)"/>' + armsvg +
                '<path fill="white" fill-rule="evenodd" d="M27.4 74.5a4.6 4.6 0 1 0 9.2 0a4.6 4.6 0 1 0 -9.2 0Z M30.2 74.5a1.8 1.8 0 1 1 3.6 0a1.8 1.8 0 1 1 -3.6 0Z"/></svg>')
    g = ''.join(f'<circle cx="54" cy="54" r="{rr}" fill="none" stroke="{GROOVE}" stroke-width=".6"/>' for rr in (19.6, 17.0, 14.4, 11.8))
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
            + (f'<rect width="108" height="108" fill="{BG}"/>' if bg else '')
            + f'<circle cx="54" cy="54" r="22" fill="{IV}"/>{g}'
            f'<circle cx="54" cy="54" r="6.2" fill="{BG}"/>'
            f'<circle cx="54" cy="54" r="4.7" fill="none" stroke="{GOLD}" stroke-width=".6"/>'
            f'<circle cx="54" cy="54" r="1.05" fill="{IV}"/>{armsvg}'
            f'<circle cx="32" cy="74.5" r="4.6" fill="{GOLD}"/><circle cx="32" cy="74.5" r="1.8" fill="{BG}"/></svg>')

def render(s, size, out, viewbox=None):
    if viewbox: s = s.replace('viewBox="0 0 108 108"', f'viewBox="{viewbox}"')
    open('/tmp/_i.svg', 'w').write(s)
    subprocess.run(['rsvg-convert', '-w', str(size), '-h', str(size), '/tmp/_i.svg', '-o', out], check=True)

if __name__ == '__main__':
    res = sys.argv[1]
    open('full4.svg', 'w').write(svg()); open('fg4.svg', 'w').write(svg(bg=False)); open('mono4.svg', 'w').write(svg(mono=True))
    for dens, mul in [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]:
        d = f'{res}/mipmap-{dens}'
        render(svg(bg=False), int(108 * mul), f'{d}/ic_launcher_foreground.png')
        render(svg(mono=True), int(108 * mul), f'{d}/ic_launcher_monochrome.png')
        L = int(48 * mul); inner = round(L * 176 / 192); off = (L - inner) // 2
        render(svg(), inner, '/tmp/_full.png', viewbox='16 16 76 76')
        full = Image.open('/tmp/_full.png').convert('RGBA')
        for name, shape in [('ic_launcher', 'rr'), ('ic_launcher_round', 'circle')]:
            m = Image.new('L', (inner * 4, inner * 4), 0); dr = ImageDraw.Draw(m)
            if shape == 'rr': dr.rounded_rectangle([0, 0, inner * 4 - 1, inner * 4 - 1], radius=int(inner * 4 * 0.17), fill=255)
            else: dr.ellipse([0, 0, inner * 4 - 1, inner * 4 - 1], fill=255)
            m = m.resize((inner, inner), Image.LANCZOS)
            canvas = Image.new('RGBA', (L, L), (0, 0, 0, 0))
            piece = full.copy(); piece.putalpha(m)
            canvas.paste(piece, (off, off), piece)
            canvas.save(f'{d}/{name}.png')
    render(svg(), 512, 'preview_full.png')
