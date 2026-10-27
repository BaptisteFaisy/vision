#!/usr/bin/env python3
"""Porte le shader Portrait gelé vers WebGL ; seul le rendu caregiver devient gris."""
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
    shader = source.split('private const val ORB_AGSL = """', 1)[1].split('"""', 1)[0]
    shader = re.sub(r'\b(?:half|float)([234])\b', r'vec\1', shader)
    shader = re.sub(r'\bhalf\b', 'float', shader)
    shader, helper = split_liquid(shader)
    shader = shader.replace('vec4 main(vec2 fc)', helper + '\nvec4 orb(vec2 fc)')
    # Les teintes uniformes sont grises ; désaturer aussi le verre et son irisation.
    shader = shader.replace('  return vec4(col *', '  col = vec3(dot(col, vec3(0.2126, 0.7152, 0.0722)));\n  return vec4(col *')
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
