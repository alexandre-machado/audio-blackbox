locals {
  permissions = {
    "Workers Scripts Write" = "com.cloudflare.api.account"
    "Workers Routes Write"  = "com.cloudflare.api.account.zone"
    "Zone Read"             = "com.cloudflare.api.account.zone"
  }
}

data "cloudflare_account_api_token_permission_groups" "deploy" {
  for_each   = local.permissions
  account_id = var.account_id
  name       = replace(urlencode(each.key), "+", "%20")
  scope      = each.value

  lifecycle {
    postcondition {
      condition = length(self.permission_groups) == 1 && alltrue([
        for group in self.permission_groups :
        group.name == each.key && contains(group.scopes, each.value) && group.is_selectable != false
      ])
      error_message = "Expected exactly one selectable permission group matching ${each.key} and ${each.value}."
    }
  }
}

data "cloudflare_zone" "machado" {
  filter = {
    name    = "machado.cc"
    account = { id = var.account_id }
    match   = "all"
  }
  lifecycle {
    postcondition {
      condition     = self.name == "machado.cc" && self.account.id == var.account_id
      error_message = "Zone lookup must resolve machado.cc in the configured account."
    }
  }
}

resource "cloudflare_account_token" "ci" {
  account_id = var.account_id
  name       = "audio-blackbox-hotsite-ci"
  expires_on = var.expires_on
  policies = [
    {
      effect = "allow"
      permission_groups = [
        { id = one(data.cloudflare_account_api_token_permission_groups.deploy["Workers Scripts Write"].permission_groups).id }
      ]
      resources = jsonencode({ "com.cloudflare.api.account.${var.account_id}" = "*" })
    },
    {
      effect = "allow"
      permission_groups = [
        { id = one(data.cloudflare_account_api_token_permission_groups.deploy["Workers Routes Write"].permission_groups).id },
        { id = one(data.cloudflare_account_api_token_permission_groups.deploy["Zone Read"].permission_groups).id }
      ]
      resources = jsonencode({ "com.cloudflare.api.account.zone.${data.cloudflare_zone.machado.id}" = "*" })
    }
  ]
  lifecycle {
    precondition {
      condition     = timecmp(var.expires_on, plantimestamp()) > 0
      error_message = "Choose a future expires_on before creating or rotating the token."
    }
  }
}
