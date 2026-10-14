import { createMaintenance, productionDependencies } from "./app.ts";
Deno.serve(
  createMaintenance(
    Deno.env.get("APP_SHARING_MAINTENANCE_KEY") ?? "",
    productionDependencies(),
  ),
);
