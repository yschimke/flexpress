# Changelog

## [0.2.0](https://github.com/yschimke/flexpress/compare/v0.1.0...v0.2.0) (2026-10-08)


### Features

* attach marks by GPOS anchors and order them as HarfBuzz does ([#16](https://github.com/yschimke/flexpress/issues/16)) ([ba87827](https://github.com/yschimke/flexpress/commit/ba87827ffc28c7ed7438ca98d03fd4d2b52778d8))
* **codegen:** add VariableFontCodegen.write for a golden-test recipe ([#9](https://github.com/yschimke/flexpress/issues/9)) ([94c25f9](https://github.com/yschimke/flexpress/commit/94c25f9862a8cedbb7299bdf1c27527d0c5fbf9c))
* **codegen:** generate for Compose UI, and standalone files with no flexpress dependency ([#21](https://github.com/yschimke/flexpress/issues/21)) ([ba0fd7b](https://github.com/yschimke/flexpress/commit/ba0fd7b8a1c58e69801e6f4e0d24d3f158f0045a))
* join Arabic and Syriac letters by the Unicode joining algorithm ([#14](https://github.com/yschimke/flexpress/issues/14)) ([eb58810](https://github.com/yschimke/flexpress/commit/eb588100ead64f455cf1b3ea9af45448256d2a1e))
* normalize text as HarfBuzz does, composing marks the font has composites for ([#19](https://github.com/yschimke/flexpress/issues/19)) ([f8bb7c3](https://github.com/yschimke/flexpress/commit/f8bb7c35a77bba22116c0637f3c7ea6050ad3964))
* shape String text with GSUB ligatures and bidirectional reordering ([#13](https://github.com/yschimke/flexpress/issues/13)) ([f223db0](https://github.com/yschimke/flexpress/commit/f223db07cd4534a57b809676d6e9397d758f0423))


### Bug Fixes

* apply a required GSUB feature once and skip null rule sets ([#17](https://github.com/yschimke/flexpress/issues/17)) ([6b00911](https://github.com/yschimke/flexpress/commit/6b0091153be33c62dc9c8ef4b2bf88bfaf47e03e))
* **deps:** resolve preview-annotations through the daemon BOM ([#15](https://github.com/yschimke/flexpress/issues/15)) ([f34b26b](https://github.com/yschimke/flexpress/commit/f34b26bed4f026ee250532bbb207ec37c33035f8))
* **preview:** render clock-driven previews from a fixed clock ([#4](https://github.com/yschimke/flexpress/issues/4)) ([1b6688e](https://github.com/yschimke/flexpress/commit/1b6688e5bcc4fb36f77709f3460be5395412e88d))
* **remote:** declare minSdk 29, as Remote Compose requires ([#11](https://github.com/yschimke/flexpress/issues/11)) ([da1701c](https://github.com/yschimke/flexpress/commit/da1701c21b5474b03a2de63938b2abd02ead6451))
* shape each script in a run with its own features, and join Syriac Alaph ([#18](https://github.com/yschimke/flexpress/issues/18)) ([af1fd6f](https://github.com/yschimke/flexpress/commit/af1fd6f148518fab0136a5d802c4fa3999a898cf))


### Performance Improvements

* skip GSUB lookups a glyph cannot start, and the shaping work text does not need ([#20](https://github.com/yschimke/flexpress/issues/20)) ([c7615f1](https://github.com/yschimke/flexpress/commit/c7615f1e97bdb7bbeace1e41cec376982f68d577))

## 0.1.0 (2026-10-07)


### Features

* import the variable-font library, Remote Compose and Compose UI runtimes and codegen ([456c220](https://github.com/yschimke/flexpress/commit/456c2201934c14aceab7a398474de1ba19478e98))
* import the variable-font library, Remote Compose and Compose UI runtimes and codegen ([3c9edd5](https://github.com/yschimke/flexpress/commit/3c9edd51d9975479ee65f07197d134c121ef77b4))
* **remote:** fold the font scale when the density is constant ([e0ccb4b](https://github.com/yschimke/flexpress/commit/e0ccb4bc4dee4723b1d2034eddecdaf27d777d9f))


### Bug Fixes

* honour scaled component offsets, guard the glyph cache, refuse non-BMP RemoteString characters ([7388fde](https://github.com/yschimke/flexpress/commit/7388fde1e0812c5a2abde908f0f075ce02327f0f))
