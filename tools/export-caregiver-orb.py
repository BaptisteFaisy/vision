#!/usr/bin/env python3
"""Porte le shader Portrait gelé vers WebGL ; la palette caregiver est injectée en JS."""
from pathlib import Path
import re

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
        raise ValueError('Déclaration ORB_AGSL introuvable dans le shader Portrait')
    shader = match.group(1)
    shader = re.sub(r'\b(?:half|float)([234])\b', r'vec\1', shader)
    shader = re.sub(r'\bhalf\b', 'float', shader)
    shader, helper = split_liquid(shader)
    shader = shader.replace('vec4 main(vec2 fc)', helper + '\nvec4 orb(vec2 fc)')
    prefix = 'precision highp float;\nuniform vec2 uSize;\n'
    main = '\nvoid main() { gl_FragColor = orb(vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y)); }\n'
    return prefix + shader + main


def export():
    fragment = web_shader(SOURCE.read_text(encoding='utf-8'))
    fragment = fragment.replace('`', '\\`').replace('${', '\\${')
    header = '// Généré depuis le shader Portrait : python tools/export-caregiver-orb.py\n'
    TARGET.write_text(header + 'export const fragmentShader = `' + fragment + '`;\n', encoding='utf-8')


if __name__ == '__main__':
    export()
