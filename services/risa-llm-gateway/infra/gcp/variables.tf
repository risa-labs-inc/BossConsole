variable "project_id" {
  description = "GCP project that owns the RISA LLM gateway."
  type        = string
}

variable "region" {
  description = "Region shared by Cloud Run and Cloud SQL."
  type        = string
}

variable "domain" {
  description = "Public API hostname."
  type        = string
  default     = "llm.risa.inc"
}

variable "gateway_image" {
  description = "Immutable Artifact Registry image for the gateway container."
  type        = string
}

variable "litellm_image" {
  description = "Immutable Artifact Registry image built with Dockerfile.litellm."
  type        = string
}

variable "supabase_url" {
  description = "Existing BOSS Supabase Auth URL."
  type        = string
  default     = "https://api.risaboss.com"
}

variable "supabase_anon_key" {
  description = "Existing public BOSS Supabase key used to validate a user session."
  type        = string
  sensitive   = true
}

variable "coreweave_secret_id" {
  description = "Existing Secret Manager secret containing the CoreWeave API token."
  type        = string
  default     = "risa-llm-coreweave-token"
}

variable "coreweave_inference_base_url" {
  description = "CoreWeave OpenAI-compatible inference endpoint ending in /v1."
  type        = string
}

variable "coreweave_served_model" {
  description = "LiteLLM provider/model name, including the openai/ prefix."
  type        = string
}

variable "pilot_email_allowlist" {
  description = "Comma-separated RISA email addresses allowed during the initial pilot."
  type        = string
}

variable "cloud_sql_tier" {
  description = "Cloud SQL machine tier; db-f1-micro is suitable only for an initial pilot."
  type        = string
  default     = "db-f1-micro"
}

variable "gateway_max_instances" {
  type    = number
  default = 10
}

variable "litellm_max_instances" {
  type    = number
  default = 10
}
