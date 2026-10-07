import { createApp, productionDependencies } from "./app.ts";
Deno.serve(createApp(productionDependencies()));
