// Host cursor shapes are signed metadata, never arbitrary CSS or image URLs.
const shapes = new Set(['default', 'pointer', 'text', 'crosshair', 'wait', 'move',
  'ew-resize', 'ns-resize', 'nesw-resize', 'nwse-resize']);
export function remoteCursor(value) { return shapes.has(value) ? value : 'default'; }
