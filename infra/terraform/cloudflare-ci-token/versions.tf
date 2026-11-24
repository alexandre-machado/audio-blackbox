terraform {
  required_version = "= 1.17.2"

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "= 5.29.0"
    }
  }
  # Intentionally use the default local backend: state contains the deploy token.
}

# Reads the owner-only master token from CLOUDFLARE_API_TOKEN.
provider "cloudflare" {}
