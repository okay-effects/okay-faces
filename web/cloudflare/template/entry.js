// the Worker's entry: the application's script (linked as an ES module) gives the fetch and the sessions' object;
// the thin page's script (the remote face's, linked on its own) given as text — leave the two lines out without one
import { okayFacesFetch, okayFacesAsset, OkayFacesSession } from "./main.js";
import page from "./page.js.txt";
okayFacesAsset("/index.js", page);
export { OkayFacesSession };
export default { fetch: (request, env) => okayFacesFetch(request, env) };
