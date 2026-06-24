// Site sandbox path (after copy): config/studio/scripts/aiassistant/user-tools/gold-price.groovy
// Invoked via InvokeSiteUserTool with toolId "gold_price" (optional args.metal — only "gold" supported).
// Data: https://mintedmetal.com/api/prices.json (CC BY 4.0 — attribute mintedmetal.com when displaying prices).

def PRICES_URL = 'https://mintedmetal.com/api/prices.json'

def metal = args?.metal?.toString()?.trim()?.toLowerCase()
if (!metal) {
  metal = 'gold'
}
if (metal != 'gold') {
  return [
    ok     : false,
    error  : true,
    message: "Unsupported metal '${metal}'. This tool only returns gold spot price.",
    toolId : toolId
  ]
}

def fetch = studio.fetchHttpUrl(PRICES_URL, 32_000)
if (fetch?.ok != true) {
  return [
    ok         : false,
    error      : true,
    message    : "Gold price API request failed: ${fetch?.message ?: 'unknown error'}",
    statusCode : fetch?.statusCode,
    finalUrl   : fetch?.finalUrl,
    toolId     : toolId
  ]
}

def body = fetch.body?.toString()?.trim()
if (!body) {
  return [ok: false, error: true, message: 'Gold price API returned an empty body.', toolId: toolId]
}

Object parsed
try {
  parsed = new groovy.json.JsonSlurper().parseText(body)
} catch (Throwable t) {
  return [
    ok     : false,
    error  : true,
    message: "Gold price API returned invalid JSON: ${t.message ?: t}",
    toolId : toolId
  ]
}

def metals = (parsed instanceof Map) ? parsed.metals : null
def gold = (metals instanceof Map) ? metals.gold : null
if (!(gold instanceof Map) || gold.price == null) {
  return [
    ok     : false,
    error  : true,
    message: 'Gold spot price missing in API response (expected metals.gold.price).',
    toolId : toolId
  ]
}

def price = (gold.price instanceof Number)
  ? ((Number) gold.price).doubleValue()
  : Double.parseDouble(gold.price.toString())

[
  ok            : true,
  metal         : 'gold',
  price         : price,
  currency      : gold.currency?.toString() ?: 'USD',
  unit          : gold.unit?.toString() ?: 'troy oz',
  fixedAt       : gold.fixedAt?.toString() ?: parsed.updatedAt?.toString(),
  source        : gold.sourceLabel?.toString() ?: gold.source?.toString(),
  previousPrice : gold.previousPrice,
  message       : "Gold spot: ${price} ${gold.currency ?: 'USD'} per ${gold.unit ?: 'troy oz'} (${gold.sourceLabel ?: gold.source ?: 'LBMA'}).",
  attribution   : 'Price data from Minted Metal (https://mintedmetal.com), CC BY 4.0 — link mintedmetal.com when showing this price to authors.',
  apiUrl        : PRICES_URL,
  siteId        : siteId
]
