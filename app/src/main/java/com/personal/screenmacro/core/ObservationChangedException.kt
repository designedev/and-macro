package com.personal.screenmacro.core

/** The overlay or bound window changed during OCR; discard this frame and retry. */
class ObservationChangedException : Exception()

/** Wait without counting an OCR error; only a freshly verified frame may resume clicks. */
class SystemUiInterruptedException : Exception()
