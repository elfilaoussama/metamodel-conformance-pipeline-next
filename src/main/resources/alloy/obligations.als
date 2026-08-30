pred ObservationConsistency {}

fun O02Violations : set Member {
  { m : Member | not one c : Classifier | m in c.declaredMembers }
}

pred O02Violation {
  some O02Violations
}

fun O03Violations : set Classifier {
  { c : Classifier | c in c.^parents }
}

pred O03Violation {
  some O03Violations
}

pred sameMemberKey[m1, m2 : Member] {
  sameMethodKey[m1, m2] or sameAttributeName[m1, m2]
}

pred localMemberHides[c : Classifier, inherited : Member] {
  some local : c.declaredMembers | sameMemberKey[local, inherited]
}

pred nearerAncestorMemberHides[c, owner : Classifier, inherited : Member] {
  some nearer : c.^parents - owner |
    owner in nearer.^parents and
    some replacement : nearer.declaredMembers |
      replacement.inheritability = INHERITABLE and
      sameMemberKey[replacement, inherited]
}

fun formalInheritedMembers[c : Classifier] : set Member {
  { inherited : Member |
    some owner : c.^parents |
      inherited in owner.declaredMembers and
      inherited.inheritability = INHERITABLE and
      not localMemberHides[c, inherited] and
      not nearerAncestorMemberHides[c, owner, inherited]
  }
}

fun O04Violations : Classifier -> Member {
  { c : Classifier, m : Member |
    (m in c.observedInheritedMembers and m not in formalInheritedMembers[c]) or
    (m not in c.observedInheritedMembers and m in formalInheritedMembers[c])
  }
}

pred O04Violation {
  some O04Violations
}

fun O05Violations : Classifier -> Member {
  { c : Classifier, m : Member |
    m in c.declaredMembers and m in c.observedInheritedMembers
  }
}

pred O05Violation {
  some O05Violations
}

pred sameMethodKey[m1, m2 : Member] {
  m1.kind = METHOD
  m2.kind = METHOD
  m1.memberName = m2.memberName
  m1.parameterTypes = m2.parameterTypes
}

pred sameAttributeName[a1, a2 : Member] {
  a1.kind = ATTRIBUTE
  a2.kind = ATTRIBUTE
  a1.memberName = a2.memberName
}

fun O08LocalMethodViolations : set Member {
  { m1 : Member |
    some c : Classifier |
      m1 in c.declaredMembers and
      some m2 : c.declaredMembers - m1 | sameMethodKey[m1, m2]
  }
}

fun O08LocalAttributeViolations : set Member {
  { a1 : Member |
    some c : Classifier |
      a1 in c.declaredMembers and
      some a2 : c.declaredMembers - a1 | sameAttributeName[a1, a2]
  }
}

fun O08LocalViolations : set Member {
  O08LocalMethodViolations + O08LocalAttributeViolations
}

pred O08LocalViolation {
  some O08LocalViolations
}
