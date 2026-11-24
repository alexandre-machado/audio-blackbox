output "token_value" {
  description = "Deploy token; stream only into the GitHub environment secret. Also stored in local state."
  value       = cloudflare_account_token.ci.value
  sensitive   = true
}
