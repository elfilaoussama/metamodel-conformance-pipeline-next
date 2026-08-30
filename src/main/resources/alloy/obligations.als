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
