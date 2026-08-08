locals {
  required_services = toset([
    "compute.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "servicenetworking.googleapis.com",
    "sqladmin.googleapis.com",
  ])
}

resource "google_project_service" "required" {
  for_each           = local.required_services
  service            = each.value
  disable_on_destroy = false
}

data "google_secret_manager_secret" "coreweave" {
  secret_id = var.coreweave_secret_id
}

resource "random_password" "gateway_database" {
  length  = 32
  special = false
}

resource "random_password" "litellm_database" {
  length  = 32
  special = false
}

resource "random_password" "litellm_master_key" {
  length  = 48
  special = false
}

resource "google_compute_network" "gateway" {
  name                    = "risa-llm"
  auto_create_subnetworks = false

  depends_on = [google_project_service.required]
}

resource "google_compute_subnetwork" "gateway" {
  name          = "risa-llm-us-central1"
  region        = var.region
  network       = google_compute_network.gateway.id
  ip_cidr_range = "10.20.0.0/24"
}

resource "google_compute_global_address" "private_services" {
  name          = "risa-llm-private-services"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.gateway.id
}

resource "google_service_networking_connection" "private_services" {
  network                 = google_compute_network.gateway.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_services.name]
}

resource "google_sql_database_instance" "main" {
  name                = "risa-llm-postgres"
  region              = var.region
  database_version    = "POSTGRES_16"
  deletion_protection = true

  settings {
    tier              = var.cloud_sql_tier
    edition           = "ENTERPRISE"
    availability_type = "ZONAL"
    disk_type         = "PD_SSD"
    disk_size         = 10
    disk_autoresize   = true

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
    }

    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.gateway.id
    }
  }

  depends_on = [google_service_networking_connection.private_services]
}

resource "google_sql_database" "gateway" {
  name     = "risa_llm_gateway"
  instance = google_sql_database_instance.main.name
}

resource "google_sql_database" "litellm" {
  name     = "litellm"
  instance = google_sql_database_instance.main.name
}

resource "google_sql_user" "gateway" {
  name     = "gateway"
  instance = google_sql_database_instance.main.name
  password = random_password.gateway_database.result
}

resource "google_sql_user" "litellm" {
  name     = "litellm"
  instance = google_sql_database_instance.main.name
  password = random_password.litellm_database.result
}

resource "google_secret_manager_secret" "gateway_database_url" {
  secret_id = "risa-llm-gateway-database-url"
  replication {
    auto {}
  }
  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "gateway_database_url" {
  secret      = google_secret_manager_secret.gateway_database_url.id
  secret_data = "postgresql://gateway:${random_password.gateway_database.result}@${google_sql_database_instance.main.private_ip_address}/risa_llm_gateway"
}

resource "google_secret_manager_secret" "litellm_database_url" {
  secret_id = "risa-llm-litellm-database-url"
  replication {
    auto {}
  }
  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "litellm_database_url" {
  secret      = google_secret_manager_secret.litellm_database_url.id
  secret_data = "postgresql://litellm:${random_password.litellm_database.result}@${google_sql_database_instance.main.private_ip_address}/litellm"
}

resource "google_secret_manager_secret" "litellm_master_key" {
  secret_id = "risa-llm-litellm-master-key"
  replication {
    auto {}
  }
  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "litellm_master_key" {
  secret      = google_secret_manager_secret.litellm_master_key.id
  secret_data = "sk-${random_password.litellm_master_key.result}"
}

resource "google_service_account" "gateway" {
  account_id   = "risa-llm-gateway"
  display_name = "RISA LLM public gateway"
}

resource "google_service_account" "litellm" {
  account_id   = "risa-llm-litellm"
  display_name = "RISA LLM private LiteLLM proxy"
}

resource "google_project_iam_member" "gateway_cloud_sql" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.gateway.email}"
}

resource "google_project_iam_member" "litellm_cloud_sql" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.litellm.email}"
}

resource "google_secret_manager_secret_iam_member" "gateway_database" {
  secret_id = google_secret_manager_secret.gateway_database_url.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.gateway.email}"
}

resource "google_secret_manager_secret_iam_member" "gateway_master_key" {
  secret_id = google_secret_manager_secret.litellm_master_key.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.gateway.email}"
}

resource "google_secret_manager_secret_iam_member" "litellm_database" {
  secret_id = google_secret_manager_secret.litellm_database_url.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.litellm.email}"
}

resource "google_secret_manager_secret_iam_member" "litellm_master_key" {
  secret_id = google_secret_manager_secret.litellm_master_key.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.litellm.email}"
}

resource "google_secret_manager_secret_iam_member" "litellm_coreweave" {
  secret_id = data.google_secret_manager_secret.coreweave.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.litellm.email}"
}

resource "google_cloud_run_v2_service" "litellm" {
  name                = "risa-llm-litellm"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = false

  template {
    service_account = google_service_account.litellm.email
    timeout         = "3600s"

    scaling {
      min_instance_count = 0
      max_instance_count = var.litellm_max_instances
    }

    containers {
      image = var.litellm_image

      resources {
        limits = {
          cpu    = "1"
          memory = "2Gi"
        }
      }

      ports {
        container_port = 8080
      }

      env {
        name  = "CW_INFERENCE_BASE_URL"
        value = var.coreweave_inference_base_url
      }
      env {
        name  = "CW_SERVED_MODEL"
        value = var.coreweave_served_model
      }
      env {
        name = "CW_API_TOKEN"
        value_source {
          secret_key_ref {
            secret  = data.google_secret_manager_secret.coreweave.secret_id
            version = "latest"
          }
        }
      }
      env {
        name = "DATABASE_URL"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.litellm_database_url.secret_id
            version = google_secret_manager_secret_version.litellm_database_url.version
          }
        }
      }
      env {
        name = "LITELLM_MASTER_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.litellm_master_key.secret_id
            version = google_secret_manager_secret_version.litellm_master_key.version
          }
        }
      }

    }

    vpc_access {
      egress = "PRIVATE_RANGES_ONLY"

      network_interfaces {
        network    = google_compute_network.gateway.name
        subnetwork = google_compute_subnetwork.gateway.name
      }
    }
  }

  depends_on = [
    google_project_iam_member.litellm_cloud_sql,
    google_secret_manager_secret_iam_member.litellm_coreweave,
    google_secret_manager_secret_iam_member.litellm_database,
    google_secret_manager_secret_iam_member.litellm_master_key,
    google_secret_manager_secret_version.litellm_database_url,
    google_secret_manager_secret_version.litellm_master_key,
    google_sql_database.litellm,
    google_sql_user.litellm,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "gateway_invokes_litellm" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.litellm.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.gateway.email}"
}

resource "google_cloud_run_v2_service" "gateway" {
  name                = "risa-llm-gateway"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER"
  deletion_protection = false

  template {
    service_account = google_service_account.gateway.email
    timeout         = "3600s"

    scaling {
      min_instance_count = 0
      max_instance_count = var.gateway_max_instances
    }

    containers {
      image = var.gateway_image

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }

      ports {
        container_port = 8080
      }

      env {
        name  = "SUPABASE_URL"
        value = var.supabase_url
      }
      env {
        name  = "SUPABASE_ANON_KEY"
        value = var.supabase_anon_key
      }
      env {
        name = "GATEWAY_DATABASE_URL"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.gateway_database_url.secret_id
            version = google_secret_manager_secret_version.gateway_database_url.version
          }
        }
      }
      env {
        name  = "LITELLM_URL"
        value = google_cloud_run_v2_service.litellm.uri
      }
      env {
        name  = "LITELLM_ID_TOKEN_AUDIENCE"
        value = google_cloud_run_v2_service.litellm.uri
      }
      env {
        name = "LITELLM_MASTER_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.litellm_master_key.secret_id
            version = google_secret_manager_secret_version.litellm_master_key.version
          }
        }
      }
      env {
        name  = "RISA_LLM_EMAIL_DOMAIN"
        value = "risalabs.ai"
      }
      env {
        name  = "RISA_LLM_ALLOWED_EMAILS"
        value = var.pilot_email_allowlist
      }
      env {
        name  = "RISA_LLM_MODEL"
        value = "coreweave-glm-5-2"
      }

    }

    vpc_access {
      egress = "PRIVATE_RANGES_ONLY"

      network_interfaces {
        network    = google_compute_network.gateway.name
        subnetwork = google_compute_subnetwork.gateway.name
      }
    }
  }

  depends_on = [
    google_cloud_run_v2_service_iam_member.gateway_invokes_litellm,
    google_project_iam_member.gateway_cloud_sql,
    google_secret_manager_secret_iam_member.gateway_database,
    google_secret_manager_secret_iam_member.gateway_master_key,
    google_secret_manager_secret_version.gateway_database_url,
    google_secret_manager_secret_version.litellm_master_key,
    google_sql_database.gateway,
    google_sql_user.gateway,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "public_gateway" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.gateway.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_compute_security_policy" "gateway" {
  name = "risa-llm-gateway"

  rule {
    action   = "throttle"
    priority = 1000
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
    rate_limit_options {
      conform_action = "allow"
      exceed_action  = "deny(429)"
      enforce_on_key = "IP"
      rate_limit_threshold {
        count        = 600
        interval_sec = 60
      }
    }
    description = "Coarse abuse ceiling; per-user limits are enforced by LiteLLM."
  }

  rule {
    action   = "allow"
    priority = 2147483647
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
  }
}

resource "google_compute_region_network_endpoint_group" "gateway" {
  name                  = "risa-llm-gateway"
  region                = var.region
  network_endpoint_type = "SERVERLESS"
  cloud_run {
    service = google_cloud_run_v2_service.gateway.name
  }
}

resource "google_compute_backend_service" "gateway" {
  name                  = "risa-llm-gateway"
  protocol              = "HTTP"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  security_policy       = google_compute_security_policy.gateway.id

  backend {
    group = google_compute_region_network_endpoint_group.gateway.id
  }
}

resource "google_compute_managed_ssl_certificate" "gateway" {
  name = "risa-llm-gateway"
  managed {
    domains = [var.domain]
  }
}

resource "google_compute_url_map" "gateway" {
  name            = "risa-llm-gateway"
  default_service = google_compute_backend_service.gateway.id
}

resource "google_compute_target_https_proxy" "gateway" {
  name             = "risa-llm-gateway"
  url_map          = google_compute_url_map.gateway.id
  ssl_certificates = [google_compute_managed_ssl_certificate.gateway.id]
}

resource "google_compute_global_address" "gateway" {
  name = "risa-llm-gateway"
}

resource "google_compute_global_forwarding_rule" "gateway_https" {
  name                  = "risa-llm-gateway-https"
  ip_address            = google_compute_global_address.gateway.address
  port_range            = "443"
  target                = google_compute_target_https_proxy.gateway.id
  load_balancing_scheme = "EXTERNAL_MANAGED"
}
