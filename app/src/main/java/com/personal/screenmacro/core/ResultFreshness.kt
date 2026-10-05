package com.personal.screenmacro.core

const val DEFAULT_RESULT_AGE_MS = 1000L
fun validResultAge(value: Long) = value in 1000L..3000L && value % 100L == 0L
fun resultAgeSeconds(value: Long) = "${value / 1000}.${value % 1000 / 100}"
