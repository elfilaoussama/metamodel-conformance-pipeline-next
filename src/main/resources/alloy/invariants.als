pred ObservationConsistency {}

fun ExclusiveDeclarationOwnershipViolations : set Member {
  { m : Member | not one c : Classifier | m in c.declaredMembers }
}

fun AcyclicGeneralizationViolations : set Classifier {
  { c : Classifier | c in c.^parents }
}

pred localMemberHides[c : Classifier, inherited : Member] {
  some local : c.declaredMembers | sameMemberKey[local, inherited]
}

pred nearerAncestorMemberHides[c, owner : Classifier, inherited : Member] {
  some nearer : c.^parents - owner |
    owner in nearer.^parents and
    some replacement : nearer.declaredMembers |
      replacement in InheritableMember and
      sameMemberKey[replacement, inherited]
}

fun formalInheritedMembers[c : Classifier] : set Member {
  { inherited : Member |
    some owner : c.^parents |
      inherited in owner.declaredMembers and
      inherited in InheritableMember and
      not localMemberHides[c, inherited] and
      not nearerAncestorMemberHides[c, owner, inherited]
  }
}

fun InheritedViewConsistencyViolations : Classifier -> Member {
  { c : Classifier, m : Member |
    (m in c.observedInheritedMembers and m not in formalInheritedMembers[c]) or
    (m not in c.observedInheritedMembers and m in formalInheritedMembers[c])
  }
}

fun LocalInheritedSeparationViolations : Classifier -> Member {
  { c : Classifier, m : Member |
    m in c.declaredMembers and m in c.observedInheritedMembers
  }
}

pred sameMemberKey[m1, m2 : Member] {
  m1.namespaceKeyRepresentative = m2.namespaceKeyRepresentative
}

fun LocalNamespaceUniquenessViolations : set Member {
  { m1 : Member |
    some c : Classifier |
      m1 in c.declaredMembers and
      some m2 : c.declaredMembers - m1 | sameMemberKey[m1, m2]
  }
}
