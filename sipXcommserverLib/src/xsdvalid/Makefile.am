## Process this file with automake to produce Makefile.in

include $(top_srcdir)/config/subdir.am

INCLUDES = -I$(top_srcdir)/include -I$(top_builddir)/config

schemas = \
	xml.xsd

bin_PROGRAMS = xsdvalid

xsdvalid_LDADD = \
	@SIPXPORT_LIBS@ \
	@XERCES_LIBS@

xsdvalid_CPPFLAGS = \
	@XERCES_CXXFLAGS@ \
	$(AM_CFLAGS)

xsdvalid_SOURCES = \
	xsdvalid.cpp

install-data-hook : install-schema

EXTRA_DATA = $(foreach xsd,$(schemas),$(xsd).in)

EXTRA_DIST = \
	$(EXTRA_DATA)

.PHONY: install-schema
install-schema: $(foreach xsd,$(schemas),$(DESTDIR)@SIPX_DATADIR@/schema/$(xsd))

$(foreach xsd,$(schemas),$(DESTDIR)@SIPX_DATADIR@/schema/$(xsd)): $(DESTDIR)@SIPX_DATADIR@/schema/% : %
	$(INSTALL) -D -m 644 $< $@

$(foreach xsd,$(schemas),$(xsd)): % : $(srcdir)/%.in
	@echo "Localize $< -> $@"
	@$(LocalizeSipXconfig) $< > $@

DISTCLEANFILES = $(schemas)
