import { fragmentShader } from './orb-shader.mjs';

const VERTEX = 'attribute vec2 position; void main() { gl_Position = vec4(position, 0., 1.); }';
const GRAYS = [0.18, 0.42, 0.72, 0.32, 0.86].flatMap(value => [value, value, value, 1]);

function compile(gl, type, source) {
  const shader = gl.createShader(type);
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader));
  return shader;
}

function programFor(gl) {
  const program = gl.createProgram();
  gl.attachShader(program, compile(gl, gl.VERTEX_SHADER, VERTEX));
  gl.attachShader(program, compile(gl, gl.FRAGMENT_SHADER, fragmentShader));
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
  gl.useProgram(program);
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  const position = gl.getAttribLocation(program, 'position');
  gl.enableVertexAttribArray(position);
  gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
  for (const name of ['uIdle', 'uPalYou', 'uPalSee']) {
    gl.uniform4fv(gl.getUniformLocation(program, name + '[0]'), GRAYS);
  }
  return program;
}

function paint(gl, program, canvas, time) {
  const ratio = Math.min(devicePixelRatio || 1, 1.5);
  const width = Math.round(canvas.clientWidth * ratio);
  const height = Math.round(canvas.clientHeight * ratio);
  if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
  gl.viewport(0, 0, width, height);
  const radius = Math.min(width * 0.335, height * 0.42);
  gl.uniform2f(gl.getUniformLocation(program, 'uSize'), width, height);
  gl.uniform2f(gl.getUniformLocation(program, 'uC'), width / 2, height / 2 + radius * 0.02 * Math.sin(time * 0.9));
  gl.uniform1f(gl.getUniformLocation(program, 'uR'), radius * (1 + 0.012 * Math.sin(time * 1.3)));
  gl.uniform1f(gl.getUniformLocation(program, 'uTime'), time);
  gl.uniform1f(gl.getUniformLocation(program, 'uLevel'), 0.4 + 0.08 * Math.sin(time * 0.5));
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
}

export function startOrb(canvas) {
  try {
    const gl = canvas.getContext('webgl', { alpha: true, antialias: true });
    if (!gl) throw new Error('WebGL unavailable');
    const program = programFor(gl);
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    let previous = -Infinity;
    function frame(now) {
      if (!document.hidden && now - previous > 33) {
        paint(gl, program, canvas, reduced.matches ? 0 : now / 1000);
        previous = now;
      }
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
    return true;
  } catch {
    canvas.parentElement.classList.add('orb-fallback');
    return false;
  }
}
