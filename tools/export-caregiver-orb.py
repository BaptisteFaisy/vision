#!/usr/bin/env python3
"""Porte le shader Portrait gelé vers WebGL ; la palette caregiver est injectée en JS.

Usage :
    python3 tools/export-caregiver-orb.py          # régénère suivi-parent/orb-shader.mjs
    python3 tools/export-caregiver-orb.py --check  # 0 = à jour, 1 = dérive (porte CI)
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
        raise ValueError('Déclaration ORB_AGSL introuvable dans le shader Portrait')
    shader = match.group(1)
    shader = re.sub(r'\b(?:half|float)([234])\b', r'vec\1', shader)
    shader = re.sub(r'\bhalf\b', 'float', shader)
    shader, helper = split_liquid(shader)
    shader = shader.replace('vec4 main(vec2 fc)', helper + '\nvec4 orb(vec2 fc)')
    prefix = 'precision highp float;\nuniform vec2 uSize;\n'
    main = '\nvoid main() { gl_FragColor = orb(vec2(gl_FragCoord.x, uSize.y - gl_FragCoord.y)); }\n'
    return prefix + shader + main


def render(source_text):
    """Contenu complet du .mjs pour le source du shader donné (pur, testable)."""
    fragment = web_shader(source_text)
    fragment = fragment.replace('`', '\\`').replace('${', '\\${')
    header = '// Généré depuis le shader Portrait : python tools/export-caregiver-orb.py\n'
    return header + 'export const fragmentShader = `' + fragment + '`;\n'


def export():
    TARGET.write_text(render(SOURCE.read_text(encoding='utf-8')), encoding='utf-8')


def check():
    """Vrai si le .mjs versionné est celui que le shader Portrait produit."""
    try:
        return TARGET.read_text(encoding='utf-8') == render(SOURCE.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return False


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--check':
        if check():
            print('OK : orb-shader.mjs à jour')
        else:
            print('DÉRIVE : suivi-parent/orb-shader.mjs ne correspond plus au shader '
                  'Portrait — régénérer (python3 tools/export-caregiver-orb.py)',
                  file=sys.stderr)
            sys.exit(1)
    elif len(sys.argv) > 1:
        print('usage : python3 tools/export-caregiver-orb.py [--check]', file=sys.stderr)
        sys.exit(2)
    else:
        export()
