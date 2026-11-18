package com.k.airscope

import java.util.Locale

/**
 * Pulls a person's name out of the names devices broadcast ("Sarah's AirPods", "Mike-iPhone",
 * "iPhone de Juan", "Johns-MacBook-Pro"). Pure logic, no Android.
 *
 * Honest limits: this is the name the OWNER typed into their device, nothing is looked up anywhere.
 * Business names ("McDonald's Free WiFi"), rooms ("Living-Room-TV") and product names ("Bose QC35") are filtered
 * out, but a person whose device is named "Office" stays unknown, and a device named "Taylor's iPhone" says
 * "Taylor", not who is carrying it right now. Without an apostrophe the word is kept as typed ("James iPhone" ->
 * James, "Johns-MacBook" -> Johns) because there is no way to tell James from Jame's.
 */
object Names {
    /** Words that say "this is a personal device" after a possessive: "<name>'s iPhone". */
    private val DEVICE_WORDS = Regex(
        "^(iphone|ipad|ipod|mac|macbook|macbook ?(pro|air)|imac|mac ?mini|mac ?studio|apple ?watch|watch|airpods( ?(pro|max))?|" +
            "beats\\w*|buds\\w*|galaxy\\s?\\w*|pixel\\s?\\w*|phone|android|tablet|laptop|notebook|pc|desktop|surface\\w*|" +
            "chromebook|kindle|fire|echo\\w*|speaker|headphones|earbuds|fitbit\\w*|garmin\\w*|oura\\w*|ring|band|" +
            "car|tesla|jeep|truck|bike|scooter|hotspot|wifi|wi-fi|network|home|house|place|room|apartment|flat|" +
            "tv|xbox|playstation|ps[345]|switch|nintendo|oneplus\\w*|xiaomi\\w*|huawei\\w*|motorola\\w*|moto\\w*|" +
            "s\\d{1,2}\\+?( ultra| fe)?|a\\d{1,2}|z ?(fold|flip)\\s?\\d*|note\\s?\\d*|nest\\w*|sonos\\w*|bose\\w*)\\b.*$",
        RegexOption.IGNORE_CASE,
    )

    /** Possessive forms that belong to businesses or products, never to a person. */
    private val NOT_A_PERSON = setOf(
        "mcdonald", "kohl", "lowe", "dillard", "trader joe", "dunkin", "domino", "applebee",
        "chili", "arby", "hardee", "kroger", "raising cane", "jimmy john", "papa john", "bj", "fry",
        "friday", "tgi friday", "ben & jerry", "kohls", "lowes", "church", "guest",
        "customer", "visitor", "student", "employee", "staff", "member", "driver", "rider", "owner", "admin",
        "everyone", "nobody", "someone", "user", "default", "free", "public", "city", "county", "school", "hotel",
        "cafe", "coffee", "bar", "pub", "shop", "store", "office", "clinic", "library", "gym", "club", "salon",
        "starbucks", "hilton", "marriott", "hyatt", "ihg", "walmart", "target", "costco", "panera", "chipotle", "subway",
        // rooms, places and adjectives people put in front of a device word
        "living", "livingroom", "kitchen", "bedroom", "bathroom", "basement", "garage", "family", "game", "dining",
        "master", "upstairs", "downstairs", "work", "old", "new", "spare", "test", "main", "shared", "kids", "kid",
        "baby", "guest", "patio", "porch", "attic", "lobby", "hall", "garden", "pool", "front", "back", "backyard",
        "sala", "casa", "cocina", "cuarto", "salon", "chambre", "maison", "bureau", "wohnzimmer", "kuche", "haus",
        "la", "el", "los", "las", "le", "les", "der", "die", "das", "the", "a", "an", "my", "our", "your",
        "mobile", "wireless", "smart", "home", "house", "apartment", "flat", "room", "car", "cars", "truck", "boat",
    )

    /**
     * Chains named after a person: a real first name on a phone ("Sam's iPhone", "Wendy's AirPods"), a business on a
     * network ("Sam's Club WiFi", "Wendy's Guest WiFi", "Wendy's TV"). The network / shared-thing words decide.
     */
    private val CHAIN_FIRST_NAMES = setOf("wendy", "denny", "macy", "carl", "sam", "harry", "ben", "jerry", "levi")
    private val NETWORK_WORDS = Regex("^(hotspot|wifi|wi-fi|network|home|house|place|room|apartment|flat|tv|car)\\b.*$", RegexOption.IGNORE_CASE)

    /**
     * A business named after its owner: "Tom's Car Wash", "Bob's Phone Shop", "Joe's TV Repair", "Mike's Bike Rentals".
     * The thing starts with a device word, so without this it would read as Tom's own car. Any trade word after the
     * first word says it is a business, whoever's name is in front. Words that also name home gadgets (bar, coffee,
     * outlet, garage, auto) are left out so "Mike's TV Sound Bar" or "Tom's Home Outlet" keep their owner.
     */
    private val BUSINESS_WORDS = Regex(
        "^\\S+.*\\b(wash|shop|store|club|cafe|pub|grill|diner|pizza|pizzeria|bakery|salon|spa|market|deli|" +
            "tavern|lounge|inn|motel|hotel|repair|repairs|tires?|towing|dealer|dealership|rentals?|restaurant|" +
            "service|services|sales|supply|supplies|detailing|wholesale)\\b.*$",
        RegexOption.IGNORE_CASE,
    )

    /** "<Device> de/di/von/van <Name>" (Spanish, Portuguese, Italian, French, German, Dutch). */
    private val OF_FORM = Regex("^(.{2,40}?)\\s+(de|di|von|van|do|da|du|des)\\s+([\\p{L}][\\p{L}'\\- ]{1,30})$", RegexOption.IGNORE_CASE)

    /** "Name's Thing", "Name’s Thing", "Names Thing" (apostrophe dropped by the device), "Name-Thing", "Name_Thing". */
    private val POSSESSIVE = Regex("^([\\p{L}][\\p{L}\\.\\- ]{0,30}?)\\s*(['’`´]s|['’`´])\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val DROPPED_APOSTROPHE = Regex("^([\\p{L}]{2,20})s\\s+(.+)$")
    private val HYPHEN = Regex("^([\\p{L}]{2,20})[-_]([\\p{L}].*)$")
    private val HOST_POSSESSIVE = Regex("^([\\p{L}]{2,20})s-(.+)$", RegexOption.IGNORE_CASE)

    /** Brand words that look like a first name in "Brand-iPhone" style hostnames. */
    private val BRANDS = setOf(
        "apple", "samsung", "google", "android", "xiaomi", "redmi", "huawei", "honor", "oneplus", "oppo", "vivo", "realme",
        "motorola", "moto", "nokia", "sony", "lg", "htc", "asus", "acer", "dell", "hp", "lenovo", "msi", "razer",
        "microsoft", "amazon", "tesla", "ford", "toyota", "honda", "bmw", "audi", "vw", "kia", "hyundai", "nissan",
        "bose", "jbl", "sonos", "beats", "garmin", "fitbit", "polar", "wahoo", "tile", "chipolo", "direct", "desktop",
        "laptop", "iphone", "ipad", "galaxy", "pixel", "nest", "ring", "wyze", "arlo", "eufy", "roku", "fire", "echo",
        "tp", "tplink", "netgear", "linksys", "att", "xfinity", "spectrum", "verizon", "tmobile", "orbi", "eero", "ubnt",
        "hidden", "unknown", "mobile", "wireless", "network", "guest", "home", "office", "free", "public", "my",
    )

    private val MULTI_SPACE = Regex("\\s+")

    /**
     * The owner's name as the device says it (capitalized), or null when the name does not clearly name a person.
     */
    fun owner(name: String?): String? {
        val n = name?.trim()?.replace(MULTI_SPACE, " ") ?: return null
        if (n.length < 4 || n.length > 64) return null
        POSSESSIVE.find(n)?.let { m ->
            val who = m.groupValues[1].trim()
            val what = m.groupValues[3].trim()
            if (DEVICE_WORDS.matches(what) && person(who, what)) return cap(who)
        }
        OF_FORM.find(n)?.let { m ->
            val what = m.groupValues[1].trim()
            val who = m.groupValues[3].trim()
            if (DEVICE_WORDS.matches(what) && person(who, what)) return cap(who)
        }
        HYPHEN.find(n)?.let { m ->
            val who = m.groupValues[1]
            val what = m.groupValues[2].replace('-', ' ').replace('_', ' ')
            if (DEVICE_WORDS.matches(what) && person(who, what)) return cap(who)
        }
        DROPPED_APOSTROPHE.find(n)?.let { m -> // "Sarahs iPhone" / "James iPhone": no apostrophe, so the word is kept as typed
            val who = m.groupValues[1] + "s"
            val what = m.groupValues[2].trim()
            if (DEVICE_WORDS.matches(what) && person(who, what) && person(m.groupValues[1], what)) return cap(who)
        }
        return null
    }

    /** Owner from a LAN hostname ("Johns-MacBook-Pro", "sarahs-iphone", "iPhone-de-Juan"); null for "DESKTOP-ABC123". */
    fun ownerFromHostname(host: String?): String? {
        var h = host?.trim()?.removeSuffix(".")?.removeSuffix(".local") ?: return null
        if (h.length < 4) return null
        HOST_POSSESSIVE.find(h)?.let { m -> // "Johns-MacBook-Pro": possessive without an apostrophe, kept as typed ("Johns")
            val who = m.groupValues[1] + "s"
            val what = m.groupValues[2].replace('-', ' ')
            if (DEVICE_WORDS.matches(what) && person(who, what) && person(m.groupValues[1], what)) return cap(who)
        }
        owner(h)?.let { return it }
        h = h.replace('-', ' ').replace('_', ' ')
        return owner(h)
    }

    private fun person(who: String, what: String): Boolean {
        val w = who.trim().trimEnd('.').lowercase(Locale.US)
        if (w.isEmpty() || w.length > 30) return false
        if (w in NOT_A_PERSON || w in BRANDS) return false
        val words = w.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 3) return false
        if (words.any { it in BRANDS || it in NOT_A_PERSON }) return false
        if (words.any { it in CHAIN_FIRST_NAMES } && NETWORK_WORDS.matches(what.trim())) return false
        if (BUSINESS_WORDS.matches(what.trim())) return false
        if (words[0] in setOf("the", "la", "el", "le", "les", "los", "las", "der", "die", "das", "de", "a", "an")) return false
        if (words.any { it.length < 2 }) return false
        // Must be letters (any script), with at most a dot/hyphen/apostrophe per word
        return words.all { it.all { c -> c.isLetter() || c == '.' || c == '-' || c == '\'' } && it.count { c -> c.isLetter() } >= 2 }
    }

    private fun cap(who: String): String = who.trim().trimEnd('.').split(' ').filter { it.isNotEmpty() }
        .joinToString(" ") { w -> if (w.all { it.isUpperCase() || !it.isLetter() } || w.all { it.isLowerCase() || !it.isLetter() }) w.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) } else w }
}
