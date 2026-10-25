// the Worker's entry: the application's script (linked as an ES module) gives the fetch and the sessions' object
import { okayFacesFetch, OkayFacesSession } from "./main.js";
export { OkayFacesSession };
export default { fetch: (request, env) => okayFacesFetch(request, env) };
