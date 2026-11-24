variable "account_id" {
  description = "Cloudflare account containing machado.cc."
  type        = string
  default     = "53f6d9e3dd6600e598c81e222c4369db"
  validation {
    condition     = can(regex("^[a-fA-F0-9]{32}$", var.account_id))
    error_message = "account_id must be a 32-character hexadecimal identifier."
  }
}

variable "expires_on" {
  description = "UTC expiry; default is one year after the 2026-11-24 rollout. Set a new future date when rotating."
  type        = string
  default     = "2027-11-24T00:00:00Z"
  nullable    = false
  validation {
    condition     = can(formatdate("YYYY-MM-DD", var.expires_on))
    error_message = "expires_on must be an RFC3339 timestamp."
  }
}
