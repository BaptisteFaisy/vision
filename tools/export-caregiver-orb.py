#!/usr/bin/env python3
"""Ports the frozen Portrait shader to WebGL; the caregiver palette is injected in JS.

Usage:
    python3 tools/export-caregiver-orb.py          # regenerates suivi-parent/orb-shader.mjs
    python3 tools/export-caregiver-orb.py --check  # 0 = fresh, 1 = drift (CI gate)
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/dev/sam3vision/app/isee/IseePortraitOrb.kt'
TARGET = ROOT / 'suivi-parent/orb-shader.mjs'


def split_liquid(shader):
    start = shader.index('  // Le liquide :')
    end = shader.index('  col *=', start)
    liquid = shader[start:end]
    helper = 'vec3 liquid(vec3 col, vec2 q) {\n' + liquid + '  return col;\n}\n'
    return shader[:start] + '  col = liquid(col, q);\n' + shader[end:], helper


def web_shader(source):
    match = re.search(r'\bconst\s+val\s+ORB_AGSL\s*=\s*"""(.*?)"""', source, re.S)
    if not match:
        raise ValueError('ORB_AGSL declaration not found in the Portrait shader')
    shader = match.group(1)
    shader = re.sub(r'\b(?:half|float)([234])\b', r'vec\1', shader)
    shader = re.sub(r'\bhalf\b', 'float', shader)
    shader, helper = split_liquid(shader)
    shader = shader.replace('vec4 main(vec2 fc)', helper + '\nvec4 orb(vec2 fc)')
    prefix = 'precision highp float;\nuniform vec2 uSize;\n'
    main = '\nvoid main() { gl_FragColor = orb(vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y)); }\n'
    return prefix + shader + main


def render(source_text):
    """Full .mjs content for the given shader source (pure, testable)."""
    fragment = web_shader(source_text)
    fragment = fragment.replace('`', '\\`').replace('${', '\\${')
    header = '// Generated from the Portrait shader: python tools/export-caregiver-orb.py\n'
    return header + 'export const fragmentShader = `' + fragment + '`;\n'


def export():
    TARGET.write_text(render(SOURCE.read_text(encoding='utf-8')), encoding='utf-8')


def check():
    """True when the committed .mjs is the one the Portrait shader yields."""
    try:
        return TARGET.read_text(encoding='utf-8') == render(SOURCE.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return False


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--check':
        if check():
            print('OK: orb-shader.mjs is fresh')
        else:
            print('DRIFT: suivi-parent/orb-shader.mjs no longer matches the '
                  'Portrait shader — regenerate (python3 tools/export-caregiver-orb.py)',
                  file=sys.stderr)
            sys.exit(1)
    elif len(sys.argv) > 1:
        print('usage: python3 tools/export-caregiver-orb.py [--check]', file=sys.stderr)
        sys.exit(2)
    else:
        export()
