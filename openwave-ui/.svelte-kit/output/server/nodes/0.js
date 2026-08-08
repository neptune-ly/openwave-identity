

export const index = 0;
let component_cache;
export const component = async () => component_cache ??= (await import('../entries/pages/_layout.svelte.js')).default;
export const universal = {
  "ssr": false,
  "prerender": false
};
export const universal_id = "src/routes/+layout.js";
export const imports = ["_app/immutable/nodes/0.DDysrIxw.js","_app/immutable/chunks/M44YMo5a.js","_app/immutable/chunks/1SOv0Uh1.js","_app/immutable/chunks/B8WnZMYa.js","_app/immutable/chunks/8FfyBiUQ.js","_app/immutable/chunks/CsUXk5vJ.js","_app/immutable/chunks/DAa3-QfS.js"];
export const stylesheets = ["_app/immutable/assets/0.ByjXiSJC.css"];
export const fonts = [];
