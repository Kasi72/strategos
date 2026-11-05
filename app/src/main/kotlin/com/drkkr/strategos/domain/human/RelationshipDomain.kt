package com.drkkr.strategos.domain.human

/** The kind of relationship the user's move sits in. It gates which delivery tactics may be offered (I-H4). */
enum class RelationshipDomain {
    INTIMATE, FAMILY, FRIENDSHIP, WORK_HIERARCHY, WORK_PEER, NEGOTIATION, ADVERSARIAL;

    /** Close relationships only ever get openly cooperative (BENIGN) tactics. */
    val isClose: Boolean get() = this == INTIMATE || this == FAMILY || this == FRIENDSHIP
}

object RelationshipDomains {

    /**
     * Deterministic mapping from the extracted `relationship` word. Unknown or missing resolves to the MOST PROTECTIVE
     * domain that fits: INTIMATE for a relational-repair (or unlabelled) archetype, otherwise FRIENDSHIP.
     */
    fun of(relationship: String?, archetype: String?): RelationshipDomain {
        val key = relationship?.trim()?.lowercase()?.replace(' ', '_')
        return when (key) {
            "spouse", "partner" -> RelationshipDomain.INTIMATE
            "parent", "child", "sibling", "relative" -> RelationshipDomain.FAMILY
            "friend" -> RelationshipDomain.FRIENDSHIP
            "manager", "report" -> RelationshipDomain.WORK_HIERARCHY
            "peer", "cofounder" -> RelationshipDomain.WORK_PEER
            "client", "vendor", "landlord" -> RelationshipDomain.NEGOTIATION
            "opponent" -> RelationshipDomain.ADVERSARIAL
            else -> {
                val a = archetype?.trim()?.uppercase().orEmpty()
                if (a.isEmpty() || a == "RELATIONAL_REPAIR") RelationshipDomain.INTIMATE else RelationshipDomain.FRIENDSHIP
            }
        }
    }
}
