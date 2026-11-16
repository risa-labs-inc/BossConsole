import { createApp } from "./app.ts";
import { restDeps } from "./deps.ts";

Deno.serve(createApp(restDeps()));
