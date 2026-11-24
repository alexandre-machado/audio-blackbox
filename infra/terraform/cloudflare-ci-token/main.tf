locals {
  permissions = {
    "Workers Scripts Write" = "com.cloudflare.api.account"
    "Workers Routes Write"  = "com.cloudflare.api.account.zone"
    "Zone Read"             = "com.cloudflare.api.account.zone"
  }
}

data "cloudflare_account_api_token_permission_groups_list" "deploy" {
  for_each   = local.permissions
  account_id = var.account_id
  # The SDK encodes query parameters; pass the literal API permission name.
  name  = each.key
  scope = each.value

  lifecycle {
    postcondition {
      condition = length(try([
        for group in self.result : group
        if try(group.name == each.key && contains(group.scopes, each.value) && try(group.is_selectable, null) != false && length(group.id) > 0, false)
      ], [])) == 1
      error_message = "Expected exactly one selectable permission group matching ${each.key} and ${each.value}."
    }
  }
}

locals {
  selected_permissions = {
    for name, scope in local.permissions : name => try(one([
      for group in data.cloudflare_account_api_token_permission_groups_list.deploy[name].result : group.id
      if try(group.name == name && contains(group.scopes, scope) && try(group.is_selectable, null) != false && length(group.id) > 0, false)
    ]), null)
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
        { id = local.selected_permissions["Workers Scripts Write"] }
      ]
      resources = jsonencode({ "com.cloudflare.api.account.${var.account_id}" = "*" })
    },
    {
      effect = "allow"
      permission_groups = [
        { id = local.selected_permissions["Workers Routes Write"] },
        { id = local.selected_permissions["Zone Read"] }
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
