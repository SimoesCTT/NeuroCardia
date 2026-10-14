package com.simoesctt.neurocardia.validation

import java.io.File

fun projectRoot(): File =
    File(System.getProperty("user.dir")).let {
        if (it.name == "validation") it.parentFile ?: it else it
    }
