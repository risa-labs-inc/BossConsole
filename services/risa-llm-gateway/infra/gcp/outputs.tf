output "gateway_ip_address" {
  description = "Create an A record for llm.risa.inc pointing to this address."
  value       = google_compute_global_address.gateway.address
}

output "gateway_cloud_run_uri" {
  value = google_cloud_run_v2_service.gateway.uri
}

output "litellm_cloud_run_uri" {
  value     = google_cloud_run_v2_service.litellm.uri
  sensitive = true
}

output "cloud_sql_connection_name" {
  value = google_sql_database_instance.main.connection_name
}
