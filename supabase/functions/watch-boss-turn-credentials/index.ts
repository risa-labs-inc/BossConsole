import { createHandler, productionDependencies } from "./app.ts";

Deno.serve(createHandler(productionDependencies()));
